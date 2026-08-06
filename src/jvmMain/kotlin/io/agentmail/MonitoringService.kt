package io.agentmail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import jakarta.mail.AuthenticationFailedException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Управляет единственной фоновой задачей опроса почты и доставкой найденных
 * упоминаний. Сервис владеет журналом доставок и закрывает его вместе с собой.
 */
class MonitoringService(
    private val settingsStore: SettingsStore,
    private val mailClient: ImapMailClient,
    private val summarizer: KoogSummarizer,
    private val telegram: TelegramClient,
    private val history: DeliveryHistoryStore,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableSnapshot = MutableStateFlow(MonitorSnapshot())

    /** Накопительное состояние мониторинга за время жизни сервиса. */
    val snapshot: StateFlow<MonitorSnapshot> = mutableSnapshot.asStateFlow()
    private var monitorJob: Job? = null

    /** Загружает последние доставки для профиля, заданного почтой, чатом и ботом. */
    fun loadHistory(settings: AppSettings, telegramBotToken: String?) {
        val normalized = settings.normalized()
        if (normalized.telegramChatId.isBlank() || normalized.imapHost.isBlank() || telegramBotToken.isNullOrBlank()) {
            update { it.copy(deliveries = emptyList()) }
            return
        }
        val records = history.recent(DeliveryKey.profile(normalized, telegramBotToken))
        update { it.copy(deliveries = records) }
    }

    /**
     * Запускает мониторинг, если он ещё не запущен. Ошибки, классифицированные
     * клиентами как постоянные, завершают задачу, остальные повторяются с задержкой.
     */
    fun start(settings: AppSettings, secrets: Secrets) {
        if (monitorJob != null) return
        update { it.copy(status = MonitorStatus.STARTING, lastError = null) }
        monitorJob = scope.launch {
            update { it.copy(status = MonitorStatus.RUNNING) }.also { addEvent("Мониторинг запущен") }
            var consecutiveFailures = 0
            while (isActive) {
                try {
                    pollOnce(settings, secrets)
                    consecutiveFailures = 0
                    delay(settings.pollIntervalMinutes * 60_000L)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    // Повтор не исправит неверные учётные данные или конфигурацию.
                    if (error is AuthenticationFailedException || error is PermanentConfigurationException || error is IllegalArgumentException) {
                        update { it.copy(status = MonitorStatus.ERROR, lastError = safeError(error)) }
                        addEvent("Мониторинг остановлен: ${safeError(error)}")
                        monitorJob = null
                        return@launch
                    }
                    consecutiveFailures++
                    // Задержка растёт до пяти минут, чтобы не перегружать недоступные сервисы.
                    val retryDelay = (15_000L * (1L shl consecutiveFailures.coerceAtMost(4))).coerceAtMost(5 * 60_000L)
                    update { it.copy(status = MonitorStatus.RUNNING, lastError = safeError(error)) }
                    addEvent("Ошибка, повтор через ${retryDelay / 1000} сек: ${safeError(error)}")
                    delay(retryDelay)
                }
            }
        }
    }

    /** Отменяет активную задачу и ожидает освобождения используемых ею ресурсов. */
    suspend fun stop() {
        val job = monitorJob ?: return
        update { it.copy(status = MonitorStatus.STOPPING) }
        job.cancelAndJoin()
        if (monitorJob === job) monitorJob = null
        update { it.copy(status = MonitorStatus.STOPPED) }
        addEvent("Мониторинг остановлен")
    }

    private suspend fun pollOnce(settings: AppSettings, secrets: Secrets) {
        val accountKey = "${settings.email}|${settings.imapUsername}|${settings.imapHost}|${settings.folder}"
        val profileKey = DeliveryKey.profile(settings, secrets.telegramBotToken)
        var cursor = settingsStore.cursor(accountKey)
        val result = mailClient.poll(settings, secrets.mailPassword, cursor)
        if (result.initialized) {
            // При первом подключении начинаем с конца ящика и не пересылаем старую историю.
            cursor = MailCursor(result.highestUid, result.uidValidity, System.currentTimeMillis())
            settingsStore.saveCursor(accountKey, cursor)
            update { it.copy(lastCheck = Instant.now(), lastError = null) }
            addEvent("Почтовый ящик подключен, ожидаю новые письма")
            return
        }

        for (message in result.messages) {
            update { it.copy(checked = it.checked + 1) }
            if (TagMatcher.matches(message, settings.tag)) {
                update { it.copy(matched = it.matched + 1) }
                val emailKey = DeliveryKey.email(message)
                if (history.isBlocked(profileKey, emailKey)) {
                    addEvent("Уже отправлялось, пропущено: ${message.subject.ifBlank { "Без темы" }.take(45)}")
                    refreshHistory(profileKey)
                    cursor = MailCursor(message.uid, result.uidValidity, System.currentTimeMillis())
                    settingsStore.saveCursor(accountKey, cursor)
                    continue
                }
                val summary = try {
                    summarizer.summarize(settings, secrets.llmApiKey, message)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    addEvent("Модель недоступна, отправлен фрагмент письма")
                    TelegramMessageFormatter.localExcerpt(message)
                }
                // Резервирование до запроса не допускает параллельную или повторную отправку.
                if (!history.beginAttempt(profileKey, emailKey, message, result.uidValidity)) continue
                val telegramMessageId = try {
                    telegram.send(
                        token = secrets.telegramBotToken,
                        chatId = settings.telegramChatId,
                        html = TelegramMessageFormatter.format(message, settings.tag, summary),
                    )
                } catch (cancellation: CancellationException) {
                    // После прерванного запроса результат неизвестен, поэтому автоматический повтор опасен.
                    history.markUnknown(profileKey, emailKey, "Отправка прервана")
                    refreshHistory(profileKey)
                    throw cancellation
                } catch (rejected: PermanentConfigurationException) {
                    // Telegram гарантированно отклонил запрос: после исправления настроек повтор безопасен.
                    history.markFailed(profileKey, emailKey, safeError(rejected))
                    refreshHistory(profileKey)
                    throw rejected
                } catch (error: Exception) {
                    // Неклассифицированный сбой считаем неоднозначным: Telegram мог уже принять сообщение.
                    history.markUnknown(profileKey, emailKey, safeError(error))
                    refreshHistory(profileKey)
                    throw error
                }
                history.markDelivered(profileKey, emailKey, telegramMessageId)
                refreshHistory(profileKey)
                update { it.copy(sent = it.sent + 1) }
                addEvent("Отправлено: ${message.subject.ifBlank { "Без темы" }.take(60)}")
            }
            // Курсор продвигается только после окончательного решения по текущему письму.
            cursor = MailCursor(message.uid, result.uidValidity, System.currentTimeMillis())
            settingsStore.saveCursor(accountKey, cursor)
            settingsStore.clearMimeFailure(accountKey, message.uid)
        }
        result.failedUid?.let { failedUid ->
            val failures = settingsStore.incrementMimeFailure(accountKey, failedUid)
            // Повреждённое письмо временно удерживает курсор, сохраняя порядок обработки.
            if (failures < MAX_MIME_ATTEMPTS) {
                error("Не удалось прочитать MIME-письмо UID $failedUid (попытка $failures)")
            }
            settingsStore.saveCursor(
                accountKey,
                MailCursor(failedUid, result.uidValidity, System.currentTimeMillis()),
            )
            settingsStore.clearMimeFailure(accountKey, failedUid)
            addEvent("Повреждённое MIME-письмо UID $failedUid пропущено после $failures попыток")
            return
        }
        settingsStore.saveCursor(
            accountKey,
            MailCursor(result.highestUid, result.uidValidity, System.currentTimeMillis()),
        )
        update { it.copy(lastCheck = Instant.now(), lastError = null) }
    }

    private fun addEvent(text: String) {
        val time = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())
        update { it.copy(events = (listOf("$time  $text") + it.events).take(8)) }
    }

    private fun update(transform: (MonitorSnapshot) -> MonitorSnapshot) {
        mutableSnapshot.value = transform(mutableSnapshot.value)
    }

    private fun refreshHistory(profileKey: String) {
        update { it.copy(deliveries = history.recent(profileKey)) }
    }

    private fun safeError(error: Throwable): String =
        (error.message ?: error::class.simpleName ?: "Неизвестная ошибка")
            .replace(Regex("bot[0-9]+:[A-Za-z0-9_-]+"), "bot***")
            .take(300)

    override fun close() {
        runBlocking { stop() }
        scope.coroutineContext[Job]?.cancel()
        history.close()
    }

    private companion object {
        const val MAX_MIME_ATTEMPTS = 3
    }
}
