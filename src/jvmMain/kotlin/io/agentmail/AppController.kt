package io.agentmail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ControllerState(
    val settings: AppSettings = AppSettings(),
    val hasSecrets: Boolean = false,
    val busy: Boolean = false,
    val notice: String? = null,
    val noticeIsError: Boolean = false,
)

class AppController(
    private val store: SettingsStore,
    private val mailClient: ImapMailClient,
    private val summarizer: KoogSummarizer,
    private val telegram: TelegramClient,
    private val monitoring: MonitoringService,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutableState = MutableStateFlow(
        ControllerState(settings = store.load(), hasSecrets = store.loadSecrets() != null)
    )
    val state: StateFlow<ControllerState> = mutableState.asStateFlow()
    val snapshot: StateFlow<MonitorSnapshot> = monitoring.snapshot

    init {
        monitoring.loadHistory(mutableState.value.settings, store.loadSecrets()?.telegramBotToken)
    }

    fun save(settings: AppSettings, enteredSecrets: Secrets?): Boolean = runCatching {
        val normalized = settings.normalized()
        val resolvedSecrets = resolveSecrets(enteredSecrets)
        if (enteredSecrets != null && resolvedSecrets == null) {
            error("Для первого сохранения заполните все три секрета")
        }
        store.save(normalized, resolvedSecrets.takeIf { enteredSecrets != null })
        monitoring.loadHistory(normalized, resolvedSecrets?.telegramBotToken)
        mutableState.value = mutableState.value.copy(
            settings = normalized,
            hasSecrets = store.loadSecrets() != null,
            notice = "Настройки сохранены в системном хранилище",
            noticeIsError = false,
        )
        true
    }.getOrElse {
        showError("Не удалось сохранить: ${it.message.orEmpty()}")
        false
    }

    fun start(settings: AppSettings, enteredSecrets: Secrets?) {
        if (snapshot.value.status == MonitorStatus.RUNNING) return
        val normalized = settings.normalized()
        val secrets = resolveSecrets(enteredSecrets)
        val errors = normalized.validationErrors(secrets != null)
        if (errors.isNotEmpty()) {
            showError(errors.joinToString(". "))
            return
        }
        checkNotNull(secrets)
        if (!save(normalized, enteredSecrets)) return
        monitoring.start(normalized, secrets)
    }

    fun stop() {
        scope.launch { monitoring.stop() }
    }

    fun testConnections(settings: AppSettings, enteredSecrets: Secrets?) {
        val normalized = settings.normalized()
        val secrets = resolveSecrets(enteredSecrets)
        val errors = normalized.validationErrors(secrets != null)
        if (errors.isNotEmpty()) {
            showError(errors.joinToString(". "))
            return
        }
        checkNotNull(secrets)
        mutableState.value = mutableState.value.copy(busy = true, notice = "Проверяю IMAP...")
        scope.launch {
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

    private fun showError(message: String) {
        mutableState.value = mutableState.value.copy(
            busy = false,
            notice = message.take(500),
            noticeIsError = true,
        )
    }

    private fun redact(message: String): String = message
        .replace(Regex("bot[0-9]+:[A-Za-z0-9_-]+"), "bot***")

    private fun resolveSecrets(entered: Secrets?): Secrets? {
        val saved = store.loadSecrets()
        if (entered == null) return saved
        return Secrets(
            mailPassword = entered.mailPassword.ifBlank { saved?.mailPassword.orEmpty() },
            llmApiKey = entered.llmApiKey.ifBlank { saved?.llmApiKey.orEmpty() },
            telegramBotToken = entered.telegramBotToken.ifBlank { saved?.telegramBotToken.orEmpty() },
        ).takeIf(Secrets::isComplete)
    }

    override fun close() {
        scope.cancel()
        monitoring.close()
        telegram.close()
        store.close()
    }
}

private fun Secrets.isComplete(): Boolean =
    mailPassword.isNotBlank() && llmApiKey.isNotBlank() && telegramBotToken.isNotBlank()
