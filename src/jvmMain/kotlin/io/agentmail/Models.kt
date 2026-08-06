package io.agentmail

import java.time.Instant

enum class LlmProviderType { OLLAMA, CUSTOM }

enum class MonitorStatus { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

/** Несекретная конфигурация почты, правила упоминания, модели и доставки. */
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

/** Чувствительные значения, которые нельзя сохранять в Preferences или писать в лог. */
data class Secrets(
    val mailPassword: String,
    val llmApiKey: String,
    val telegramBotToken: String,
)

/** Письмо, где [uid] действителен только внутри текущего IMAP `UIDVALIDITY`. */
data class MailMessage(
    val uid: Long,
    val messageId: String? = null,
    val from: String,
    val subject: String,
    val body: String,
    val receivedAt: Instant?,
)

/**
 * Состояние доставки: `UNKNOWN` блокирует опасный повтор при неясном результате,
 * а `FAILED` разрешает повтор после гарантированного отказа.
 */
enum class DeliveryStatus { ATTEMPTING, DELIVERED, UNKNOWN, FAILED }

/** Элемент устойчивой истории доставки для отображения в UI. */
data class DeliveryRecord(
    val emailKey: String,
    val sender: String,
    val subject: String,
    val status: DeliveryStatus,
    val receivedAt: Instant?,
    val updatedAt: Instant,
)

/** Текущее состояние фонового сервиса и накопительные счётчики его работы. */
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

/** Убирает пробелы и унифицирует пути и URL перед сохранением и использованием. */
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

/** Возвращает готовые для UI сообщения обо всех найденных ошибках конфигурации. */
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

/** Проверяет комплект секретов с учётом выбранного поставщика модели. */
fun Secrets?.isCompleteFor(settings: AppSettings): Boolean =
    this != null && mailPassword.isNotBlank() && telegramBotToken.isNotBlank() &&
        (settings.llmProvider != LlmProviderType.CUSTOM || llmApiKey.isNotBlank())

/** Проверяет, что Ollama-модель подтверждена актуальным локальным каталогом. */
fun AppSettings.hasAvailableOllamaModel(models: List<String>): Boolean =
    llmProvider != LlmProviderType.OLLAMA || ollamaModel in models
