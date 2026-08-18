package io.agentmail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Доступное UI состояние контроллера. [settings] отражает последнюю загруженную или
 * успешно сохранённую конфигурацию, но не каждое редактирование формы: незавершённый
 * ввод Compose хранит локально. Секреты наружу не передаются, вместо них публикуются
 * только признаки наличия обязательного набора и пользовательского API-ключа.
 *
 * Поля Ollama описывают последний запрос каталога моделей, а [busy], [notice] и
 * [noticeIsError] — состояние и результат выполняемой пользовательской операции.
 */
data class ControllerState(
    val settings: AppSettings = AppSettings(),
    val hasMailAndTelegramSecrets: Boolean = false,
    val hasCustomApiKey: Boolean = false,
    val hasGitLabToken: Boolean = false,
    val ollamaModels: List<String> = emptyList(),
    val ollamaModelsLoading: Boolean = false,
    val ollamaModelsError: String? = null,
    val busy: Boolean = false,
    val notice: String? = null,
    val noticeIsError: Boolean = false,
    val issueLabelsBusy: Boolean = false,
    val issueLabelsNotice: String? = null,
    val issueLabelsNoticeIsError: Boolean = false,
)

/**
 * Связывает Compose UI с хранилищем настроек, внешними клиентами и мониторингом.
 * Контроллер является границей между изменяемой локальной формой и сохранённой
 * конфигурацией: разрешает частично введённые секреты, валидирует итоговый набор и
 * публикует только безопасное состояние для отображения.
 *
 * При закрытии отменяет UI-операции и освобождает переданные ему закрываемые
 * ресурсы. После [close] контроллер использовать нельзя.
 */
class AppController(
    private val store: SettingsStore,
    private val mailClient: ImapMailClient,
    private val summarizer: KoogSummarizer,
    private val telegram: TelegramClient,
    private val gitLab: GitLabClient,
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
            hasGitLabToken = store.loadGitLabAccessToken().isNotBlank(),
        )
    })
    /** Сохранённая конфигурация и состояние операций формы без значений секретов. */
    val state: StateFlow<ControllerState> = mutableState.asStateFlow()
    /** Снимок фонового мониторинга; контроллер не дублирует, а напрямую экспортирует поток сервиса. */
    val snapshot: StateFlow<MonitorSnapshot> = monitoring.snapshot

    init {
        monitoring.loadHistory(mutableState.value.settings, store.loadSecrets()?.telegramBotToken)
    }

    /**
     * Нормализует и сохраняет настройки, затем синхронизирует [state] и историю
     * доставки с новым профилем. Возвращает `true` только после успешной записи.
     *
     * `null` в [enteredSecrets] означает, что пользователь не редактировал ни одного
     * секрета, и оставляет keyring без изменений. Если объект передан, его пустые
     * поля дополняются ранее сохранёнными значениями, поэтому можно заменить только
     * один секрет. Если передан хотя бы один новый секрет, итоговый набор должен быть
     * полным; пустая форма всё же позволяет отдельно сохранить несекретные настройки.
     */
    fun save(settings: AppSettings, enteredSecrets: Secrets?): Boolean = runCatching {
        val normalized = settings.normalized()
        val resolvedSecrets = resolveSecrets(enteredSecrets, normalized)
        val gitLabOriginChanged = normalized.gitLabBaseUrl.canonicalGitLabOrigin() !=
            mutableState.value.settings.gitLabBaseUrl.canonicalGitLabOrigin()
        if (
            normalized.gitLabBaseUrl.isNotBlank() && gitLabOriginChanged &&
            enteredSecrets?.gitLabAccessToken.isNullOrBlank()
        ) {
            error("Для нового GitLab Base URL введите access token заново")
        }
        if (enteredSecrets != null && !resolvedSecrets.isCompleteFor(normalized)) {
            error("Для первого сохранения заполните все обязательные секреты")
        }
        store.save(normalized, resolvedSecrets.takeIf { enteredSecrets != null })
        monitoring.loadHistory(normalized, resolvedSecrets?.telegramBotToken)
        val savedSecrets = store.loadSecrets()
        mutableState.value = mutableState.value.copy(
            settings = normalized,
            hasMailAndTelegramSecrets = savedSecrets != null,
            hasCustomApiKey = !savedSecrets?.llmApiKey.isNullOrBlank(),
            hasGitLabToken = store.loadGitLabAccessToken().isNotBlank(),
            notice = "Настройки сохранены в системном хранилище",
            noticeIsError = false,
        )
        true
    }.getOrElse {
        showError("Не удалось сохранить: ${it.message.orEmpty()}")
        false
    }

    /**
     * Проверяет конфигурацию, сохраняет её и запускает фоновый мониторинг. Сервис
     * получает нормализованный снимок настроек и разрешённые секреты на весь срок
     * запуска; последующее редактирование формы само по себе на него не влияет.
     */
    fun start(settings: AppSettings, enteredSecrets: Secrets?) {
        if (snapshot.value.status == MonitorStatus.RUNNING) return
        val normalized = settings.normalized()
        val secrets = resolveSecrets(enteredSecrets, normalized)
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
     * Настройки при этом не сохраняются, а секреты разрешаются по тем же правилам,
     * что и при сохранении формы.
     */
    fun testConnections(settings: AppSettings, enteredSecrets: Secrets?) {
        val normalized = settings.normalized()
        val secrets = resolveSecrets(enteredSecrets, normalized)
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
                if (normalized.gitLabBaseUrl.isNotBlank()) {
                    mutableState.value = mutableState.value.copy(notice = "Проверяю GitLab...")
                    gitLab.test(normalized.gitLabBaseUrl, secrets.gitLabAccessToken)
                }
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

    /**
     * Асинхронно обновляет список локально установленных completion-моделей Ollama.
     * Параллельный запрос не запускается; ошибка запроса и пустой каталог получают
     * разные сообщения, чтобы валидация объясняла причину отсутствия выбора модели.
     */
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

    @Synchronized
    internal fun applyIssueLabels(issueUrl: String, selectedLabels: Set<TaskLabelOption>) {
        if (mutableState.value.issueLabelsBusy) return
        val baseUrl = mutableState.value.settings.gitLabBaseUrl
        val token = store.loadGitLabAccessToken()
        val labels = taskLabelCatalog.filter(selectedLabels::contains).map(TaskLabelOption::value)
        val validationError = when {
            baseUrl.isBlank() || token.isBlank() -> "Сначала сохраните GitLab Base URL и access token в настройках"
            issueUrl.isBlank() -> "Вставьте ссылку на задачу GitLab"
            labels.isEmpty() -> "Выберите хотя бы одну метку"
            else -> null
        }
        if (validationError != null) {
            showIssueLabelsError(validationError)
            return
        }

        mutableState.value = mutableState.value.copy(
            issueLabelsBusy = true,
            issueLabelsNotice = "Применяю метки...",
            issueLabelsNoticeIsError = false,
        )
        scope.launch {
            try {
                val issueIid = gitLab.addIssueLabels(baseUrl, token, issueUrl, labels)
                mutableState.value = mutableState.value.copy(
                    issueLabelsBusy = false,
                    issueLabelsNotice = "Метки применены к задаче #$issueIid",
                    issueLabelsNoticeIsError = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showIssueLabelsError("Не удалось применить метки. Проверьте ссылку и доступ GitLab")
            }
        }
    }

    /**
     * Объединяет общую валидацию конфигурации с проверкой Ollama-модели по последнему
     * загруженному каталогу. Возвращаемые строки предназначены непосредственно для UI.
     */
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

    private fun showIssueLabelsError(message: String) {
        mutableState.value = mutableState.value.copy(
            issueLabelsBusy = false,
            issueLabelsNotice = message.take(500),
            issueLabelsNoticeIsError = true,
        )
    }

    // Не допускаем отображения полного bot token в сообщении об ошибке.
    private fun redact(message: String): String = message
        .replace(Regex("bot[0-9]+:[A-Za-z0-9_-]+"), "bot***")

    /**
     * Собирает эффективный набор секретов для операции. Отсутствующий объект означает
     * использование keyring как есть, а пустые поля переданного объекта наследуют
     * сохранённые значения; явное удаление секрета этим контрактом не предусмотрено.
     */
    private fun resolveSecrets(entered: Secrets?, targetSettings: AppSettings): Secrets? {
        val saved = store.loadSecrets()
        val sameGitLabOrigin = targetSettings.gitLabBaseUrl.canonicalGitLabOrigin() ==
            mutableState.value.settings.gitLabBaseUrl.canonicalGitLabOrigin()
        if (entered == null) {
            return saved?.copy(
                gitLabAccessToken = saved.gitLabAccessToken.takeIf { sameGitLabOrigin }.orEmpty(),
            )
        }
        // Пустое поле означает «оставить сохранённое значение», а не удалить его.
        return Secrets(
            mailPassword = entered.mailPassword.ifBlank { saved?.mailPassword.orEmpty() },
            llmApiKey = entered.llmApiKey.ifBlank { saved?.llmApiKey.orEmpty() },
            telegramBotToken = entered.telegramBotToken.ifBlank { saved?.telegramBotToken.orEmpty() },
            gitLabAccessToken = entered.gitLabAccessToken.ifBlank {
                saved?.gitLabAccessToken?.takeIf { sameGitLabOrigin }.orEmpty()
            },
        )
    }

    /**
     * Сначала запрашивает отмену незавершённых UI-корутин, не ожидая их завершения,
     * затем закрывает мониторинг и сетевые клиенты, после чего освобождает keyring.
     * Закрытие мониторинга также закрывает принадлежащий ему устойчивый журнал доставок.
     */
    override fun close() {
        scope.cancel()
        monitoring.close()
        ollamaModels.close()
        telegram.close()
        gitLab.close()
        store.close()
    }
}
