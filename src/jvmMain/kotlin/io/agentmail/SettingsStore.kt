package io.agentmail

import com.github.javakeyring.Keyring
import java.security.MessageDigest
import java.util.prefs.Preferences

/**
 * Хранит обычные настройки и IMAP-курсоры в [Preferences], а чувствительные
 * значения — только в системном keyring.
 */
class SettingsStore : AutoCloseable {
    private val preferences = Preferences.userRoot().node("io/agentmail")
    private val keyring = Keyring.create()

    /** Загружает несекретные настройки, подставляя безопасные значения по умолчанию. */
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
    )

    /** Сохраняет настройки; [secrets] равный `null` не изменяет данные в keyring. */
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
        secrets?.let {
            keyring.setPassword(SERVICE, MAIL_PASSWORD, it.mailPassword)
            keyring.setPassword(SERVICE, TELEGRAM_TOKEN, it.telegramBotToken)
            if (it.llmApiKey.isNotBlank()) keyring.setPassword(SERVICE, LLM_API_KEY, it.llmApiKey)
        }
        preferences.flush()
    }

    /** Возвращает секреты, если сохранены обязательные для всех режимов почта и Telegram. */
    fun loadSecrets(): Secrets? {
        val secrets = Secrets(
            mailPassword = password(MAIL_PASSWORD),
            llmApiKey = password(LLM_API_KEY),
            telegramBotToken = password(TELEGRAM_TOKEN),
        )
        return secrets.takeIf { it.mailPassword.isNotBlank() && it.telegramBotToken.isNotBlank() }
    }

    /** Загружает позицию последнего опроса для конкретного почтового аккаунта. */
    fun cursor(accountKey: String): MailCursor {
        val key = accountKey.preferenceKey()
        return MailCursor(
            lastUid = preferences.getLong("lastUid.$key", 0L),
            uidValidity = preferences.getLong("uidValidity.$key", 0L),
            checkedAtEpochMillis = preferences.getLong("checkedAt.$key", 0L),
        )
    }

    /** Сохраняет UID и время последнего завершённого этапа обработки. */
    fun saveCursor(accountKey: String, cursor: MailCursor) {
        val key = accountKey.preferenceKey()
        preferences.putLong("lastUid.$key", cursor.lastUid)
        preferences.putLong("uidValidity.$key", cursor.uidValidity)
        preferences.putLong("checkedAt.$key", cursor.checkedAtEpochMillis)
        preferences.flush()
    }

    /** Увеличивает счётчик неудачных разборов конкретного MIME-письма. */
    fun incrementMimeFailure(accountKey: String, uid: Long): Int {
        val key = "mimeFailure.${accountKey.preferenceKey()}.$uid"
        val count = preferences.getInt(key, 0) + 1
        preferences.putInt(key, count)
        preferences.flush()
        return count
    }

    /** Удаляет счётчик MIME-ошибок после успеха или контролируемого пропуска письма. */
    fun clearMimeFailure(accountKey: String, uid: Long) {
        preferences.remove("mimeFailure.${accountKey.preferenceKey()}.$uid")
    }

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
    }
}

/** Старый Qwen-профиль однократно переводится на локальный режим при следующем сохранении. */
internal fun storedLlmProvider(value: String): LlmProviderType =
    if (value == LlmProviderType.CUSTOM.name) LlmProviderType.CUSTOM else LlmProviderType.OLLAMA

/**
 * Позиция IMAP-опроса. [lastUid] имеет смысл только для указанного [uidValidity],
 * а время используется для восстановления после смены пространства UID.
 */
data class MailCursor(
    val lastUid: Long = 0L,
    val uidValidity: Long = 0L,
    val checkedAtEpochMillis: Long = 0L,
)
