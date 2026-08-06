package io.agentmail

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Контрактные тесты HTTP-представления запросов к Telegram Bot API. */
class TelegramClientTest {
    /**
     * Проверяет сериализацию всех значимых параметров `sendMessage` и возврат `message_id`.
     * MockEngine фиксирует wire-контракт без обращения к Telegram и без настоящего bot-токена.
     */
    @Test
    fun `send serializes request as json`() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/bottest-token/sendMessage", request.url.encodedPath)
            val textContent = request.body as TextContent
            assertTrue(textContent.contentType.match(ContentType.Application.Json))

            val body = textContent.text
            val json = Json.parseToJsonElement(body).jsonObject
            assertEquals("42", json.getValue("chat_id").jsonPrimitive.content)
            assertEquals("<b>Hello</b>", json.getValue("text").jsonPrimitive.content)
            assertEquals("HTML", json.getValue("parse_mode").jsonPrimitive.content)
            assertTrue(json.getValue("disable_web_page_preview").jsonPrimitive.boolean)

            respond(
                content = """{"ok":true,"result":{"message_id":777}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            expectSuccess = false
        }

        TelegramClient(http).use { client ->
            assertEquals(777, client.send("test-token", "42", "<b>Hello</b>"))
        }
    }
}
