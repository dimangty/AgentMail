package io.agentmail

import com.github.javakeyring.Keyring
import java.security.MessageDigest
import java.util.prefs.Preferences

/**
 * Постоянное хранилище конфигурации и прогресса почтового монитора.
 *
 * Обычные настройки, IMAP-курсоры и счётчики MIME-ошибок сохраняются в
 * пользовательском узле [Preferences], а чувствительные значения — только в
 * системном keyring. Экземпляр владеет подключением к keyring и должен быть закрыт.
 */
class SettingsStore : AutoCloseable {
    private val preferences = Preferences.userRoot().node("io/agentmail")
    private val keyring = Keyring.create()

    /**
     * Загружает несекретные настройки, подставляя значения по умолчанию для ещё не
     * сохранённых ключей. Старое отсутствие отдельного IMAP-логина компенсируется
     * значением email, а неизвестный идентификатор LLM-провайдера мигрирует в Ollama.
     */
    fun load(): AppSettings = AppSettings(
        email = preferences.get("email", ""),
        imapUsername = preferences.get("imapUsername", preferences.get("email", "")),
        imapHost = preferences.get("imapHost", ""),
        imapPort = preferences.getInt("imapPort", 993),
        useStartTls = preferences.getBoolean("useStartTls", false),
        folder = preferences.get("folder", "INBOX"),
        tag = preferences.get("tag", ""),
        pollIntervalMinutes = preferences.getInt("pollInterval", 2),
        llmProvider = storedLlmProvider(preferences.get("llmProvider", LlmProviderType.OLLAMA.name)),
        ollamaModel = preferences.get("ollamaModel", ""),
        customBaseUrl = preferences.get("customBaseUrl", ""),
        customChatPath = preferences.get("customChatPath", "v1/chat/completions"),
        customModel = preferences.get("customModel", ""),
        telegramChatId = preferences.get("telegramChatId", ""),
        gitLabBaseUrl = preferences.get("gitLabBaseUrl", ""),
    )

    /**
     * Сохраняет нормализованные текстовые настройки и при необходимости [secrets].
     *
     * `null` не изменяет данные keyring. Почтовый пароль и Telegram-токен записываются
     * как переданы, а пустой API-ключ не стирает ранее сохранённый ключ.
     * Устаревшие настройки Qwen удаляются при каждом сохранении.
     */
    fun save(settings: AppSettings, secrets: Secrets?) {
        preferences.put("email", settings.email.trim())
        preferences.put("imapUsername", settings.imapUsername.trim())
        preferences.put("imapHost", settings.imapHost.trim())
        preferences.putInt("imapPort", settings.imapPort)
        preferences.putBoolean("useStartTls", settings.useStartTls)
        preferences.put("folder", settings.folder.trim())
        preferences.put("tag", settings.tag.trim())
        preferences.putInt("pollInterval", settings.pollIntervalMinutes)
        preferences.put("llmProvider", settings.llmProvider.name)
        preferences.put("ollamaModel", settings.ollamaModel.trim())
        preferences.remove("qwenRegion")
        preferences.remove("qwenModel")
        preferences.put("customBaseUrl", settings.customBaseUrl.trim().trimEnd('/'))
        preferences.put("customChatPath", settings.customChatPath.trim().trimStart('/'))
        preferences.put("customModel", settings.customModel.trim())
        preferences.put("telegramChatId", settings.telegramChatId.trim())
        preferences.put("gitLabBaseUrl", settings.gitLabBaseUrl.trim().trimEnd('/'))
        secrets?.let {
            keyring.setPassword(SERVICE, MAIL_PASSWORD, it.mailPassword)
            keyring.setPassword(SERVICE, TELEGRAM_TOKEN, it.telegramBotToken)
            if (it.llmApiKey.isNotBlank()) keyring.setPassword(SERVICE, LLM_API_KEY, it.llmApiKey)
            if (it.gitLabAccessToken.isNotBlank()) keyring.setPassword(SERVICE, GITLAB_ACCESS_TOKEN, it.gitLabAccessToken)
        }
        preferences.flush()
    }

    /**
     * Загружает комплект секретов из keyring.
     *
     * Возвращает `null`, если отсутствует почтовый пароль или Telegram-токен,
     * обязательные для любого режима. Наличие API-ключа здесь не проверяется,
     * поскольку для Ollama он не нужен; полноту для провайдера проверяет
     * `Secrets.isCompleteFor`.
     */
    fun loadSecrets(): Secrets? {
        val secrets = Secrets(
            mailPassword = password(MAIL_PASSWORD),
            llmApiKey = password(LLM_API_KEY),
            telegramBotToken = password(TELEGRAM_TOKEN),
            gitLabAccessToken = password(GITLAB_ACCESS_TOKEN),
        )
        return secrets.takeIf { it.mailPassword.isNotBlank() && it.telegramBotToken.isNotBlank() }
    }

    internal fun loadGitLabAccessToken(): String = password(GITLAB_ACCESS_TOKEN)

    /**
     * Загружает позицию последнего опроса для [accountKey].
     * Ключ аккаунта преобразуется в короткий хеш, чтобы исходный идентификатор
     * не использовался в именах записей `Preferences`.
     */
    fun cursor(accountKey: String): MailCursor {
        val key = accountKey.preferenceKey()
        return MailCursor(
            lastUid = preferences.getLong("lastUid.$key", 0L),
            uidValidity = preferences.getLong("uidValidity.$key", 0L),
            checkedAtEpochMillis = preferences.getLong("checkedAt.$key", 0L),
        )
    }

    /**
     * Сохраняет UID, соответствующий ему `UIDVALIDITY` и время последнего
     * завершённого этапа обработки [accountKey]. Поля записываются в `Preferences`
     * отдельно, поэтому метод не предоставляет транзакционной атомарности всего
     * курсора. Явный `flush` перед возвратом передаёт изменения постоянному хранилищу.
     */
    fun saveCursor(accountKey: String, cursor: MailCursor) {
        val key = accountKey.preferenceKey()
        preferences.putLong("lastUid.$key", cursor.lastUid)
        preferences.putLong("uidValidity.$key", cursor.uidValidity)
        preferences.putLong("checkedAt.$key", cursor.checkedAtEpochMillis)
        preferences.flush()
    }

    /**
     * Увеличивает и возвращает устойчивый счётчик неудачных разборов MIME-письма.
     * Ключ включает аккаунт и числовой IMAP UID, но не `UIDVALIDITY`. Поэтому после
     * смены поколения папки повторно использованный UID может унаследовать старый
     * счётчик, если прежняя запись не была очищена успешной обработкой или пропуском.
     */
    fun incrementMimeFailure(accountKey: String, uid: Long): Int {
        val key = "mimeFailure.${accountKey.preferenceKey()}.$uid"
        val count = preferences.getInt(key, 0) + 1
        preferences.putInt(key, count)
        preferences.flush()
        return count
    }

    /**
     * Удаляет счётчик MIME-ошибок после успешного разбора или контролируемого
     * пропуска письма, чтобы прежние отказы не влияли на дальнейшую обработку UID.
     */
    fun clearMimeFailure(accountKey: String, uid: Long) {
        preferences.remove("mimeFailure.${accountKey.preferenceKey()}.$uid")
    }

    /** Освобождает нативные ресурсы системного keyring, которыми владеет хранилище. */
    override fun close() = keyring.close()

    private fun password(key: String): String = runCatching { keyring.getPassword(SERVICE, key).orEmpty() }
        .getOrDefault("")

    // Короткий хеш не включает исходный account key в имя Preferences, но не является шифрованием.
    private fun String.preferenceKey(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .take(12)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val SERVICE = "AgentMail"
        const val MAIL_PASSWORD = "mail-password"
        const val LLM_API_KEY = "llm-api-key"
        const val TELEGRAM_TOKEN = "telegram-bot-token"
        const val GITLAB_ACCESS_TOKEN = "gitlab-access-token"
    }
}

/**
 * Преобразует сохранённое имя LLM-провайдера в поддерживаемый вариант.
 * Только точное имя `CUSTOM` сохраняет корпоративный режим; старый Qwen-профиль
 * и любые неизвестные значения безопасно переводятся в локальный режим Ollama.
 */
internal fun storedLlmProvider(value: String): LlmProviderType =
    if (value == LlmProviderType.CUSTOM.name) LlmProviderType.CUSTOM else LlmProviderType.OLLAMA

/**
 * Устойчивая позиция завершённого IMAP-опроса для одного почтового аккаунта.
 *
 * [lastUid] имеет смысл только для указанного [uidValidity]. При смене пространства
 * UID нельзя продолжать последовательный обход по старому значению, поэтому
 * [checkedAtEpochMillis] служит временной границей для восстановления позиции.
 * Нулевые значения обозначают отсутствие сохранённого прогресса.
 */
data class MailCursor(
    val lastUid: Long = 0L,
    val uidValidity: Long = 0L,
    val checkedAtEpochMillis: Long = 0L,
)
