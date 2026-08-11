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
 * Координирует единственную фоновую задачу опроса почты: читает новые письма,
 * отбирает упоминания, получает краткое содержание и доставляет результат в Telegram.
 *
 * Сервис хранит оперативное состояние в [snapshot], а устойчивые позиции IMAP и
 * журнал дедупликации делегирует [SettingsStore] и [DeliveryHistoryStore]. Экземпляр
 * владеет журналом доставок и закрывает его вместе с собой.
 */
class MonitoringService(
    private val settingsStore: SettingsStore,
    private val mailClient: ImapMailClient,
    private val summarizer: KoogSummarizer,
    private val telegram: TelegramClient,
    private val gitLab: GitLabIssueReviewer,
    private val history: DeliveryHistoryStore,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableSnapshot = MutableStateFlow(MonitorSnapshot())

    /**
     * Накопительное состояние мониторинга за время жизни сервиса. Поток содержит
     * статус текущей задачи, счётчики, последние события и проекцию устойчивой
     * истории доставок; подписчики получают только неизменяемое представление.
     */
    val snapshot: StateFlow<MonitorSnapshot> = mutableSnapshot.asStateFlow()
    private var monitorJob: Job? = null

    /**
     * Загружает последние доставки для профиля, заданного почтой, чатом и ботом.
     * Если профиль ещё нельзя однозначно построить, очищает список в снимке, чтобы
     * UI не показывал историю от ранее выбранной конфигурации.
     */
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
     * Запускает мониторинг с неизменяемым для этой задачи снимком [settings] и
     * [secrets], если задача ещё не запущена. Для применения новой конфигурации
     * текущий мониторинг требуется остановить и запустить заново.
     *
     * Ошибки аутентификации и конфигурации завершают задачу в состоянии ошибки.
     * Ограниченная экспоненциальная задержка повторяет только цикл опроса до
     * резервирования доставки. Если результат уже начатого Telegram-запроса
     * неоднозначен, запись получает `UNKNOWN` и автоматически не отправляется снова.
     * Отмена всегда пробрасывается и не маскируется логикой повторов.
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

    /**
     * Отменяет активную задачу и дожидается завершения её `finally`-блоков. При
     * наличии задачи переводит снимок в [MonitorStatus.STOPPED]; повторный вызов
     * после её удаления ничего не делает.
     */
    suspend fun stop() {
        val job = monitorJob ?: return
        update { it.copy(status = MonitorStatus.STOPPING) }
        job.cancelAndJoin()
        if (monitorJob === job) monitorJob = null
        update { it.copy(status = MonitorStatus.STOPPED) }
        addEvent("Мониторинг остановлен")
    }

    /**
     * Выполняет один последовательный цикл от чтения IMAP до фиксации доставок.
     * Сначала загружается устойчивый курсор аккаунта, затем IMAP возвращает только
     * письма после него либо временной перехлёст при смене `UIDVALIDITY`. Первый
     * успешный контакт лишь устанавливает курсор на конец ящика, не рассылая архив.
     *
     * Каждое разобранное письмо обрабатывается строго по порядку. Для совпавшего
     * письма сначала проверяется устойчивый ключ дедупликации, затем строится summary
     * и непосредственно перед сетевой отправкой атомарно резервируется попытка.
     * Курсор продвигается только после окончательного решения по письму: пропуска,
     * подтверждённой доставки или уже существующей блокирующей записи. Поэтому сбой
     * до такого решения оставляет письмо для следующего опроса.
     *
     * Отмена во время LLM-запроса безопасно оставляет письмо без резервации. Отмена
     * или неясная ошибка во время Telegram-запроса переводит попытку в `UNKNOWN`:
     * сервер мог принять сообщение, поэтому следующий опрос увидит дедупликацию и
     * не создаст дубликат. Только гарантированный отказ помечается как `FAILED` и
     * разрешает повтор после исправления конфигурации.
     */
    private suspend fun pollOnce(settings: AppSettings, secrets: Secrets) {
        // Курсор ограничен IMAP-аккаунтом, а профиль доставки дополнительно учитывает чат и бота.
        val accountKey = "${settings.email}|${settings.imapUsername}|${settings.imapHost}|${settings.folder}"
        val profileKey = DeliveryKey.profile(settings, secrets.telegramBotToken)
        val gitLabProfileKey = settings.gitLabBaseUrl.takeIf(String::isNotBlank)?.let {
            DeliveryKey.gitLabProfile(settings)
        }
        var cursor = settingsStore.cursor(accountKey)
        val result = mailClient.poll(settings, secrets.mailPassword, cursor)
        if (result.initialized) {
            // При первом подключении граница фиксируется до обработки: старую историю не пересылаем.
            cursor = MailCursor(result.highestUid, result.uidValidity, System.currentTimeMillis())
            settingsStore.saveCursor(accountKey, cursor)
            update { it.copy(lastCheck = Instant.now(), lastError = null) }
            addEvent("Почтовый ящик подключен, ожидаю новые письма")
            return
        }

        // Порядок важен: перескакивать через письмо до устойчивого решения по нему нельзя.
        for (message in result.messages) {
            update { it.copy(checked = it.checked + 1) }
            if (TagMatcher.matches(message, settings.tag)) {
                update { it.copy(matched = it.matched + 1) }
                if (!processTelegram(settings, secrets, message, result.uidValidity, profileKey)) {
                    throw ActionInProgressException("Telegram delivery is already in progress")
                }
            }
            val gitLabRef = GitLabMergeNotificationParser.parse(message, settings.gitLabBaseUrl)
            if (gitLabRef != null && gitLabProfileKey != null) {
                if (!processGitLab(settings, secrets, message, gitLabRef, gitLabProfileKey)) {
                    throw ActionInProgressException("GitLab action is already in progress")
                }
            }
            // Запись после обработки создаёт устойчивую границу: после перезапуска письмо не читается повторно.
            cursor = MailCursor(message.uid, result.uidValidity, System.currentTimeMillis())
            settingsStore.saveCursor(accountKey, cursor)
            settingsStore.clearMimeFailure(accountKey, message.uid)
        }
        result.failedUid?.let { failedUid ->
            val failures = settingsStore.incrementMimeFailure(accountKey, failedUid)
            // Повреждённое письмо удерживает курсор; лимит не позволяет ему навсегда заблокировать ящик.
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
        // Без MIME-сбоя можно зафиксировать всю вычисленную IMAP-клиентом границу, включая пустой опрос.
        settingsStore.saveCursor(
            accountKey,
            MailCursor(result.highestUid, result.uidValidity, System.currentTimeMillis()),
        )
        update { it.copy(lastCheck = Instant.now(), lastError = null) }
    }

    private suspend fun processTelegram(
        settings: AppSettings,
        secrets: Secrets,
        message: MailMessage,
        uidValidity: Long,
        profileKey: String,
    ): Boolean {
        val emailKey = DeliveryKey.email(message)
        if (history.isBlocked(profileKey, emailKey)) {
            addEvent("Уже отправлялось, пропущено: ${message.subject.ifBlank { "Без темы" }.take(45)}")
            refreshHistory(profileKey)
            return true
        }
        val summary = try {
            summarizer.summarize(settings, secrets.llmApiKey, message)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            addEvent("Модель недоступна, отправлен фрагмент письма")
            TelegramMessageFormatter.localExcerpt(message)
        }
        if (!history.beginAttempt(profileKey, emailKey, message, uidValidity)) return false
        val telegramMessageId = try {
            telegram.send(
                token = secrets.telegramBotToken,
                chatId = settings.telegramChatId,
                html = TelegramMessageFormatter.format(message, settings.tag, summary),
            )
        } catch (cancellation: CancellationException) {
            history.markUnknown(profileKey, emailKey, "Отправка прервана")
            refreshHistory(profileKey)
            throw cancellation
        } catch (rejected: PermanentConfigurationException) {
            history.markFailed(profileKey, emailKey, safeError(rejected))
            refreshHistory(profileKey)
            throw rejected
        } catch (error: Exception) {
            history.markUnknown(profileKey, emailKey, safeError(error))
            refreshHistory(profileKey)
            throw error
        }
        history.markDelivered(profileKey, emailKey, telegramMessageId)
        refreshHistory(profileKey)
        update { it.copy(sent = it.sent + 1) }
        addEvent("Отправлено: ${message.subject.ifBlank { "Без темы" }.take(60)}")
        return true
    }

    private suspend fun processGitLab(
        settings: AppSettings,
        secrets: Secrets,
        message: MailMessage,
        ref: GitLabMergeRequestRef,
        profileKey: String,
    ): Boolean {
        val emailKey = DeliveryKey.email(message)
        when (history.gitLabActionStatus(profileKey, emailKey)) {
            GitLabActionStatus.SUCCEEDED -> return true
            GitLabActionStatus.ATTEMPTING -> return false
            GitLabActionStatus.FAILED, null -> Unit
        }
        if (!history.beginGitLabAction(profileKey, emailKey)) return false
        val issueIid = try {
            gitLab.reviewMergedIssue(settings.gitLabBaseUrl, secrets.gitLabAccessToken, ref)
        } catch (cancellation: CancellationException) {
            history.markGitLabActionFailed(profileKey, emailKey, "Действие прервано")
            throw cancellation
        } catch (error: Exception) {
            history.markGitLabActionFailed(profileKey, emailKey, safeError(error))
            throw error
        }
        history.markGitLabActionSucceeded(profileKey, emailKey)
        issueIid?.let { addEvent("GitLab issue #$it: Reviewed") }
        return true
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

    /** Возвращает ограниченный безопасный текст ошибки, скрывая похожий на Telegram token фрагмент. */
    private fun safeError(error: Throwable): String =
        (error.message ?: error::class.simpleName ?: "Неизвестная ошибка")
            .replace(Regex("bot[0-9]+:[A-Za-z0-9_-]+"), "bot***")
            .take(300)

    /**
     * Синхронно останавливает мониторинг, отменяет корневой scope и закрывает журнал.
     * После вызова экземпляр больше не предназначен для повторного запуска.
     */
    override fun close() {
        runBlocking { stop() }
        scope.coroutineContext[Job]?.cancel()
        history.close()
    }

    private companion object {
        const val MAX_MIME_ATTEMPTS = 3
    }
}

private class ActionInProgressException(message: String) : Exception(message)
