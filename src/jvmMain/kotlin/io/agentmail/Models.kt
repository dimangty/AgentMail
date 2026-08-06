package io.agentmail

import java.time.Instant

enum class LlmProviderType { QWEN, CUSTOM }

enum class MonitorStatus { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

data class AppSettings(
    val email: String = "",
    val imapUsername: String = "",
    val imapHost: String = "",
    val imapPort: Int = 993,
    val useStartTls: Boolean = false,
    val folder: String = "INBOX",
    val tag: String = "",
    val pollIntervalMinutes: Int = 2,
    val llmProvider: LlmProviderType = LlmProviderType.QWEN,
    val qwenRegion: String = "International",
    val qwenModel: String = "qwen-plus",
    val customBaseUrl: String = "",
    val customChatPath: String = "v1/chat/completions",
    val customModel: String = "",
    val telegramChatId: String = "",
)

data class Secrets(
    val mailPassword: String,
    val llmApiKey: String,
    val telegramBotToken: String,
)

data class MailMessage(
    val uid: Long,
    val messageId: String? = null,
    val from: String,
    val subject: String,
    val body: String,
    val receivedAt: Instant?,
)

enum class DeliveryStatus { ATTEMPTING, DELIVERED, UNKNOWN, FAILED }

data class DeliveryRecord(
    val emailKey: String,
    val sender: String,
    val subject: String,
    val status: DeliveryStatus,
    val receivedAt: Instant?,
    val updatedAt: Instant,
)

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

fun AppSettings.normalized(): AppSettings = copy(
    email = email.trim(),
    imapUsername = imapUsername.trim(),
    imapHost = imapHost.trim(),
    folder = folder.trim(),
    tag = tag.trim(),
    qwenModel = qwenModel.trim(),
    customBaseUrl = customBaseUrl.trim().trimEnd('/'),
    customChatPath = customChatPath.trim().trimStart('/'),
    customModel = customModel.trim(),
    telegramChatId = telegramChatId.trim(),
)

fun AppSettings.validationErrors(secretsAvailable: Boolean): List<String> = buildList {
    if (!email.contains('@')) add("Укажите корпоративный email")
    if (imapUsername.isBlank()) add("Укажите логин почты")
    if (imapHost.isBlank()) add("Укажите IMAP host")
    if (imapPort !in 1..65535) add("IMAP port должен быть от 1 до 65535")
    if (!tag.startsWith('@') || tag.length < 3) add("Тег должен начинаться с @")
    if (pollIntervalMinutes !in 1..1440) add("Интервал должен быть от 1 до 1440 минут")
    if (llmProvider == LlmProviderType.CUSTOM) {
        if (!customBaseUrl.startsWith("https://")) add("Base URL корпоративной модели должен использовать HTTPS")
        if (customModel.isBlank()) add("Укажите ID корпоративной модели")
    }
    if (telegramChatId.isBlank()) add("Укажите Telegram chat ID")
    if (!secretsAvailable) add("Сохраните пароли и API-токены")
}
