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

/**
 * Клиент Telegram Bot API с таймаутами, JSON-сериализацией и проверкой ответов.
 *
 * Экземпляр владеет [HttpClient], включая переданный внутреннему конструктору для тестов, и
 * закрывает его в [close]. Ошибки отправки разделяются на гарантированные конфигурационные
 * отказы и неоднозначные сбои. После неоднозначного результата мониторинг блокирует
 * автоматический повтор, поскольку Telegram мог принять сообщение до возникновения ошибки.
 */
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

    /**
     * Проверяет токен через `getMe`, затем отправляет тестовое сообщение в [chatId].
     *
     * Вторая операция принципиальна: валидный бот-токен ещё не подтверждает существование чата
     * и право бота писать в него. Метод завершается успешно только после обеих проверок.
     */
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
     *
     * Метод не экранирует [html] повторно: вызывающий код отвечает за корректность Telegram HTML.
     * HTTP 400, 401 и 403 классифицируются как [PermanentConfigurationException], потому что
     * повтор того же запроса не изменит невалидный текст, токен или права чата. Транспортные сбои
     * и прочие HTTP-статусы остаются обычными исключениями, но в контуре мониторинга считаются
     * неоднозначным результатом и не приводят к автоматической повторной отправке.
     * Telegram также кодирует отказ полем `ok=false` при успешном HTTP-статусе; такой отказ
     * считается постоянным, поскольку сервер уже принял и семантически проверил запрос.
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
            // Эти клиентские ответы требуют изменить токен, чат, права или HTML. Остальные статусы,
            // включая серверные 5xx, остаются неоднозначными: вызывающий код решает, допустим ли повтор.
            if (response.status.value in listOf(400, 401, 403)) throw PermanentConfigurationException(message)
            error(message)
        }
        val result = runCatching { response.body<TelegramResponse>() }.getOrElse {
            error("Некорректный ответ Telegram: ${response.bodyAsText().take(200)}")
        }
        // HTTP 2xx означает только успешную доставку запроса до Bot API. Поле ok остаётся
        // авторитетным результатом Telegram и может сообщить семантический отказ отдельно.
        if (!result.ok) throw PermanentConfigurationException(result.description ?: "Telegram sendMessage failed")
        return result.result?.messageId
    }

    /** Закрывает принадлежащий экземпляру HTTP-клиент и освобождает его сетевые ресурсы. */
    override fun close() = http.close()

    private fun endpoint(token: String, method: String) = "https://api.telegram.org/bot$token/$method"
}

/**
 * Ошибка конфигурации Telegram, для которой автоматический повтор неизменного запроса бессмыслен.
 * Обработчик доставки может отличать её от временных сетевых и серверных сбоев и ожидать
 * вмешательства пользователя вместо расходования повторных попыток.
 */
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
