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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** Контрактные тесты отбора локальных генеративных моделей из ответов Ollama API. */
class OllamaModelsClientTest {
    /**
     * Проверяет весь путь фильтрации: cloud- и embedding-записи исключаются, известная
     * completion-модель принимается сразу, а модель без capabilities уточняется через `/api/show`.
     * Список запрошенных описаний дополнительно фиксирует, что лишние сетевые вызовы не выполняются.
     */
    @Test
    fun `returns only local completion models`() = runTest {
        val inspectedModels = mutableListOf<String>()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/tags" -> {
                    assertEquals(HttpMethod.Get, request.method)
                    respondJson(
                        """
                        {
                          "models": [
                            {"model":"qwen3:8b","capabilities":["completion","tools"]},
                            {"model":"nomic-embed-text:latest","capabilities":["embedding"]},
                            {"model":"legacy:latest"},
                            {"model":"qwen3:cloud","remote_model":"qwen3","remote_host":"ollama.com"}
                          ]
                        }
                        """.trimIndent()
                    )
                }

                "/api/show" -> {
                    assertEquals(HttpMethod.Post, request.method)
                    val body = (request.body as TextContent).text
                    val model = Json.parseToJsonElement(body).jsonObject.getValue("model").jsonPrimitive.content
                    inspectedModels += model
                    respondJson("""{"capabilities":["completion"]}""")
                }

                else -> error("Unexpected request: ${request.url}")
            }
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            expectSuccess = false
        }

        OllamaModelsClient(http).use { client ->
            assertEquals(listOf("legacy:latest", "qwen3:8b"), client.availableModels())
        }
        assertEquals(listOf("legacy:latest"), inspectedModels)
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(content: String) = respond(
        content = content,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )
}
