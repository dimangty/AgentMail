package io.agentmail

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Клиент Telegram Bot API с таймаутами, JSON-сериализацией и проверкой ответов. */
class TelegramClient internal constructor(private val http: HttpClient) : AutoCloseable {
    constructor() : this(createHttpClient())

    private companion object {
        fun createHttpClient() = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
        expectSuccess = false
        }
    }

    /** Проверяет токен через `getMe` и отправляет тестовое сообщение в указанный чат.  */
    suspend fun test(token: String, chatId: String) {
        val response = http.get(endpoint(token, "getMe"))
        if (!response.status.isSuccess()) {
            error("Telegram отклонил токен (${response.status.value})")
        }
        val result = response.body<TelegramResponse>()
        check(result.ok) { result.description ?: "Telegram token test failed" }
        send(token, chatId, "<b>AgentMail</b>: тестовое уведомление доставлено.")
    }

    /**
     * Отправляет заранее экранированный Telegram HTML и возвращает ID сообщения,
     * если он присутствует в ответе API.
     */
    suspend fun send(token: String, chatId: String, html: String): Long? {
        val response = http.post(endpoint(token, "sendMessage")) {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(
                SendMessageRequest(
                    chatId = chatId,
                    text = html,
                    parseMode = "HTML",
                    disableWebPagePreview = true,
                )
            )
        }
        if (!response.status.isSuccess()) {
            val description = runCatching { response.body<TelegramResponse>().description }.getOrNull()
            val message = "Telegram sendMessage: ${response.status.value} ${description.orEmpty()}".trim()
            // Эти ответы не исправятся повтором без изменения токена, чата или текста.
            if (response.status.value in listOf(400, 401, 403)) throw PermanentConfigurationException(message)
            error(message)
        }
        val result = runCatching { response.body<TelegramResponse>() }.getOrElse {
            error("Некорректный ответ Telegram: ${response.bodyAsText().take(200)}")
        }
        // Telegram может вернуть ошибку в JSON даже при успешном HTTP-статусе.
        if (!result.ok) throw PermanentConfigurationException(result.description ?: "Telegram sendMessage failed")
        return result.result?.messageId
    }

    override fun close() = http.close()

    private fun endpoint(token: String, method: String) = "https://api.telegram.org/bot$token/$method"
}

/** Ошибка конфигурации, для которой автоматический повтор запроса бессмыслен. */
class PermanentConfigurationException(message: String) : IllegalStateException(message)

@Serializable
private data class SendMessageRequest(
    @SerialName("chat_id") val chatId: String,
    val text: String,
    @SerialName("parse_mode") val parseMode: String,
    @SerialName("disable_web_page_preview") val disableWebPagePreview: Boolean,
)

@Serializable
private data class TelegramResponse(
    val ok: Boolean,
    val description: String? = null,
    val result: TelegramMessageResult? = null,
)

@Serializable
private data class TelegramMessageResult(
    @SerialName("message_id") val messageId: Long,
)
