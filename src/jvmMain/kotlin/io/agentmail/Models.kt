package io.agentmail

import java.time.Instant

/**
 * Поддерживаемые способы получения краткого содержания письма: локальная Ollama
 * либо корпоративный OpenAI-совместимый HTTP endpoint.
 *
 * Выбор варианта определяет, какие поля настроек и секреты обязательны при
 * валидации, но не меняет формат итогового Telegram-уведомления.
 */
enum class LlmProviderType { OLLAMA, CUSTOM }

/**
 * Состояние жизненного цикла фонового почтового монитора, публикуемое в UI.
 * Переходные состояния позволяют отличить выполняющийся запуск или остановку
 * от устойчивых состояний, а [ERROR] означает завершение работы с ошибкой.
 */
enum class MonitorStatus { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

/**
 * Несекретная конфигурация полного контура обработки: чтения IMAP, поиска
 * упоминания, обращения к модели и отправки результата в Telegram.
 *
 * Экземпляр может содержать ещё не нормализованные или невалидные пользовательские
 * данные. Перед использованием их следует привести через [normalized] и проверить
 * через [validationErrors]; доступность выбранной Ollama-модели проверяется отдельно
 * через [hasAvailableOllamaModel]. Пароли и токены намеренно вынесены в [Secrets].
 */
data class AppSettings(
    val email: String = "",
    val imapUsername: String = "",
    val imapHost: String = "",
    val imapPort: Int = 993,
    val useStartTls: Boolean = false,
    val folder: String = "INBOX",
    val tag: String = "",
    val pollIntervalMinutes: Int = 2,
    val llmProvider: LlmProviderType = LlmProviderType.OLLAMA,
    val ollamaModel: String = "",
    val customBaseUrl: String = "",
    val customChatPath: String = "v1/chat/completions",
    val customModel: String = "",
    val telegramChatId: String = "",
)

/**
 * Чувствительные учётные данные внешних систем.
 *
 * Эти значения предназначены для системного хранилища секретов и не должны
 * попадать в `Preferences`, диагностические события или журналы приложения.
 * API-ключ модели может быть пустым для локального провайдера Ollama.
 */
data class Secrets(
    val mailPassword: String,
    val llmApiKey: String,
    val telegramBotToken: String,
)

/**
 * Извлечённое из IMAP письмо в форме, необходимой последующей обработке.
 *
 * [uid] является адресом письма только внутри текущего пространства IMAP
 * `UIDVALIDITY`, поэтому не используется как долговечная идентичность доставки.
 * Для неё предпочтителен [messageId], а при его отсутствии строится отпечаток
 * содержимого. [receivedAt] может отсутствовать у некорректного или неполного письма.
 */
data class MailMessage(
    val uid: Long,
    val messageId: String? = null,
    val from: String,
    val subject: String,
    val body: String,
    val receivedAt: Instant?,
)

/**
 * Состояние одной попытки доставки Telegram-уведомления.
 *
 * `ATTEMPTING` резервирует письмо до сетевого вызова, `DELIVERED` фиксирует
 * подтверждённый успех, `UNKNOWN` блокирует опасный повтор при неясном результате,
 * а `FAILED` разрешает новую попытку после гарантированного отказа.
 */
enum class DeliveryStatus { ATTEMPTING, DELIVERED, UNKNOWN, FAILED }

/**
 * Проекция устойчивой записи доставки для отображения в UI.
 *
 * В модель намеренно не входят технические данные резервации, IMAP UID и текст
 * ошибки: она отражает только идентичность письма, текущее состояние и времена,
 * нужные пользователю для просмотра недавней истории.
 */
data class DeliveryRecord(
    val emailKey: String,
    val sender: String,
    val subject: String,
    val status: DeliveryStatus,
    val receivedAt: Instant?,
    val updatedAt: Instant,
)

/**
 * Неизменяемый снимок состояния фонового монитора для публикации в UI.
 *
 * Счётчики являются накопительными в рамках жизненного цикла монитора, [events]
 * содержат пользовательские диагностические сообщения, а [deliveries] — актуальную
 * проекцию устойчивой истории. Пустой снимок описывает ещё не запускавшийся сервис.
 */
data class MonitorSnapshot(
    val status: MonitorStatus = MonitorStatus.STOPPED,
    val lastCheck: Instant? = null,
    val checked: Int = 0,
    val matched: Int = 0,
    val sent: Int = 0,
    val lastError: String? = null,
    val events: List<String> = emptyList(),
    val deliveries: List<DeliveryRecord> = emptyList(),
)

/**
 * Возвращает копию настроек в канонической текстовой форме перед сохранением
 * и использованием.
 *
 * Метод убирает внешние пробелы, завершающий `/` у базового URL и начальный `/`
 * у относительного пути чата. Он не исправляет значения и не заменяет
 * [validationErrors].
 */
fun AppSettings.normalized(): AppSettings = copy(
    email = email.trim(),
    imapUsername = imapUsername.trim(),
    imapHost = imapHost.trim(),
    folder = folder.trim(),
    tag = tag.trim(),
    ollamaModel = ollamaModel.trim(),
    customBaseUrl = customBaseUrl.trim().trimEnd('/'),
    customChatPath = customChatPath.trim().trimStart('/'),
    customModel = customModel.trim(),
    telegramChatId = telegramChatId.trim(),
)

/**
 * Проверяет настройки и соответствующий им комплект [secrets], возвращая все
 * обнаруженные ошибки как готовые для UI сообщения.
 *
 * Требования к модели зависят от [AppSettings.llmProvider]: Ollama требует выбранную
 * локальную модель, корпоративный провайдер — HTTPS URL, ID модели и API-ключ.
 * Это структурная проверка полей и секретов. Пустой список ещё не подтверждает,
 * что выбранная Ollama-модель присутствует в актуальном локальном каталоге: перед
 * запуском контроллер отдельно применяет [hasAvailableOllamaModel].
 */
fun AppSettings.validationErrors(secrets: Secrets?): List<String> = buildList {
    if (!email.contains('@')) add("Укажите корпоративный email")
    if (imapUsername.isBlank()) add("Укажите логин почты")
    if (imapHost.isBlank()) add("Укажите IMAP host")
    if (imapPort !in 1..65535) add("IMAP port должен быть от 1 до 65535")
    if (!tag.startsWith('@') || tag.length < 3) add("Тег должен начинаться с @")
    if (pollIntervalMinutes !in 1..1440) add("Интервал должен быть от 1 до 1440 минут")
    when (llmProvider) {
        LlmProviderType.OLLAMA -> if (ollamaModel.isBlank()) add("Выберите установленную модель Ollama")
        LlmProviderType.CUSTOM -> {
            if (!customBaseUrl.startsWith("https://")) add("Base URL корпоративной модели должен использовать HTTPS")
            if (customModel.isBlank()) add("Укажите ID корпоративной модели")
        }
    }
    if (telegramChatId.isBlank()) add("Укажите Telegram chat ID")
    if (secrets?.mailPassword.isNullOrBlank()) add("Сохраните пароль почты")
    if (secrets?.telegramBotToken.isNullOrBlank()) add("Сохраните Telegram bot token")
    if (llmProvider == LlmProviderType.CUSTOM && secrets?.llmApiKey.isNullOrBlank()) {
        add("Сохраните API key корпоративной модели")
    }
}

/**
 * Возвращает `true`, если сохранены обязательные почтовый пароль и Telegram-токен,
 * а для корпоративного провайдера также непустой API-ключ модели.
 *
 * Для Ollama [Secrets.llmApiKey] намеренно не участвует в результате.
 */
fun Secrets?.isCompleteFor(settings: AppSettings): Boolean =
    this != null && mailPassword.isNotBlank() && telegramBotToken.isNotBlank() &&
        (settings.llmProvider != LlmProviderType.CUSTOM || llmApiKey.isNotBlank())

/**
 * Проверяет доступность выбранной Ollama-модели по актуальному локальному каталогу.
 * Для любого другого провайдера возвращает `true`, поскольку переданный список
 * относится исключительно к Ollama.
 */
fun AppSettings.hasAvailableOllamaModel(models: List<String>): Boolean =
    llmProvider != LlmProviderType.OLLAMA || ollamaModel in models
