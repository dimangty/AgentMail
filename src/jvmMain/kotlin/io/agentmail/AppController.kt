package io.agentmail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Состояние формы настроек и выполняемой из UI операции. */
data class ControllerState(
    val settings: AppSettings = AppSettings(),
    val hasMailAndTelegramSecrets: Boolean = false,
    val hasCustomApiKey: Boolean = false,
    val ollamaModels: List<String> = emptyList(),
    val ollamaModelsLoading: Boolean = false,
    val ollamaModelsError: String? = null,
    val busy: Boolean = false,
    val notice: String? = null,
    val noticeIsError: Boolean = false,
)

/**
 * Связывает Compose UI с хранилищем настроек, внешними клиентами и мониторингом.
 * При закрытии освобождает все переданные ему ресурсы.
 */
class AppController(
    private val store: SettingsStore,
    private val mailClient: ImapMailClient,
    private val summarizer: KoogSummarizer,
    private val telegram: TelegramClient,
    private val monitoring: MonitoringService,
    private val ollamaModels: OllamaModelsClient,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutableState = MutableStateFlow(store.load().let { settings ->
        val secrets = store.loadSecrets()
        ControllerState(
            settings = settings,
            hasMailAndTelegramSecrets = secrets != null,
            hasCustomApiKey = !secrets?.llmApiKey.isNullOrBlank(),
        )
    })
    val state: StateFlow<ControllerState> = mutableState.asStateFlow()
    val snapshot: StateFlow<MonitorSnapshot> = monitoring.snapshot

    init {
        monitoring.loadHistory(mutableState.value.settings, store.loadSecrets()?.telegramBotToken)
    }

    /**
     * Нормализует и сохраняет настройки. `null` в [enteredSecrets] оставляет keyring
     * без изменений, а пустые отдельные поля дополняются ранее сохранёнными значениями.
     */
    fun save(settings: AppSettings, enteredSecrets: Secrets?): Boolean = runCatching {
        val normalized = settings.normalized()
        val resolvedSecrets = resolveSecrets(enteredSecrets)
        if (enteredSecrets != null && !resolvedSecrets.isCompleteFor(normalized)) {
            error(
                if (normalized.llmProvider == LlmProviderType.OLLAMA) {
                    "Для первого сохранения заполните пароль почты и Telegram token"
                } else {
                    "Для первого сохранения заполните пароль почты, API key и Telegram token"
                }
            )
        }
        store.save(normalized, resolvedSecrets.takeIf { enteredSecrets != null })
        monitoring.loadHistory(normalized, resolvedSecrets?.telegramBotToken)
        val savedSecrets = store.loadSecrets()
        mutableState.value = mutableState.value.copy(
            settings = normalized,
            hasMailAndTelegramSecrets = savedSecrets != null,
            hasCustomApiKey = !savedSecrets?.llmApiKey.isNullOrBlank(),
            notice = "Настройки сохранены в системном хранилище",
            noticeIsError = false,
        )
        true
    }.getOrElse {
        showError("Не удалось сохранить: ${it.message.orEmpty()}")
        false
    }

    /** Проверяет конфигурацию, сохраняет её и запускает фоновый мониторинг. */
    fun start(settings: AppSettings, enteredSecrets: Secrets?) {
        if (snapshot.value.status == MonitorStatus.RUNNING) return
        val normalized = settings.normalized()
        val secrets = resolveSecrets(enteredSecrets)
        val errors = connectionErrors(normalized, secrets)
        if (errors.isNotEmpty()) {
            showError(errors.joinToString(". "))
            return
        }
        checkNotNull(secrets)
        if (!save(normalized, enteredSecrets)) return
        monitoring.start(normalized, secrets)
    }

    /** Асинхронно останавливает мониторинг, не блокируя UI-поток. */
    fun stop() {
        scope.launch { monitoring.stop() }
    }

    /**
     * Последовательно проверяет IMAP, LLM и Telegram, обновляя текст прогресса.
     * Проверка Telegram отправляет реальное тестовое уведомление в указанный чат.
     */
    fun testConnections(settings: AppSettings, enteredSecrets: Secrets?) {
        val normalized = settings.normalized()
        val secrets = resolveSecrets(enteredSecrets)
        val errors = connectionErrors(normalized, secrets)
        if (errors.isNotEmpty()) {
            showError(errors.joinToString(". "))
            return
        }
        checkNotNull(secrets)
        mutableState.value = mutableState.value.copy(busy = true, notice = "Проверяю IMAP...")
        scope.launch {
            // Последовательный порядок позволяет UI точно показывать текущий этап проверки.
            runCatching {
                mailClient.test(normalized, secrets.mailPassword)
                mutableState.value = mutableState.value.copy(notice = "Проверяю модель...")
                summarizer.test(normalized, secrets.llmApiKey)
                mutableState.value = mutableState.value.copy(notice = "Проверяю Telegram...")
                telegram.test(secrets.telegramBotToken, normalized.telegramChatId)
            }.onSuccess {
                mutableState.value = mutableState.value.copy(
                    busy = false,
                    notice = "Все подключения работают",
                    noticeIsError = false,
                )
            }.onFailure {
                showError("Проверка не пройдена: ${redact(it.message.orEmpty())}")
            }
        }
    }

    /** Асинхронно обновляет список локально установленных completion-моделей Ollama. */
    fun refreshOllamaModels() {
        if (mutableState.value.ollamaModelsLoading) return
        mutableState.value = mutableState.value.copy(ollamaModelsLoading = true, ollamaModelsError = null)
        scope.launch {
            runCatching { ollamaModels.availableModels() }
                .onSuccess { models ->
                    mutableState.value = mutableState.value.copy(
                        ollamaModels = models,
                        ollamaModelsLoading = false,
                        ollamaModelsError = if (models.isEmpty()) "В Ollama нет локальных chat-моделей" else null,
                    )
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        ollamaModels = emptyList(),
                        ollamaModelsLoading = false,
                        ollamaModelsError = "Не удалось получить модели Ollama: ${redact(error.message.orEmpty())}",
                    )
                }
        }
    }

    private fun connectionErrors(settings: AppSettings, secrets: Secrets?): List<String> = buildList {
        addAll(settings.validationErrors(secrets))
        if (
            settings.llmProvider == LlmProviderType.OLLAMA &&
            settings.ollamaModel.isNotBlank() &&
            !settings.hasAvailableOllamaModel(mutableState.value.ollamaModels)
        ) {
            add(
                when {
                    mutableState.value.ollamaModelsLoading -> "Дождитесь загрузки моделей Ollama"
                    mutableState.value.ollamaModelsError != null -> mutableState.value.ollamaModelsError.orEmpty()
                    else -> "Выберите модель из актуального списка Ollama"
                }
            )
        }
    }

    private fun showError(message: String) {
        mutableState.value = mutableState.value.copy(
            busy = false,
            notice = message.take(500),
            noticeIsError = true,
        )
    }

    // Не допускаем отображения полного bot token в сообщении об ошибке.
    private fun redact(message: String): String = message
        .replace(Regex("bot[0-9]+:[A-Za-z0-9_-]+"), "bot***")

    private fun resolveSecrets(entered: Secrets?): Secrets? {
        val saved = store.loadSecrets()
        if (entered == null) return saved
        // Пустое поле означает «оставить сохранённое значение», а не удалить его.
        return Secrets(
            mailPassword = entered.mailPassword.ifBlank { saved?.mailPassword.orEmpty() },
            llmApiKey = entered.llmApiKey.ifBlank { saved?.llmApiKey.orEmpty() },
            telegramBotToken = entered.telegramBotToken.ifBlank { saved?.telegramBotToken.orEmpty() },
        )
    }

    override fun close() {
        scope.cancel()
        monitoring.close()
        ollamaModels.close()
        telegram.close()
        store.close()
    }
}
