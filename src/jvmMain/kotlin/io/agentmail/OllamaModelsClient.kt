package io.agentmail

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Получает из локальной Ollama список моделей, пригодных для генерации текста.
 *
 * Экземпляр владеет переданным через внутренний конструктор [HttpClient] так же, как клиентом,
 * созданным публичным конструктором: вызов [close] всегда закрывает его. Инъекция конструктора
 * предназначена для подмены транспорта в тестах, а не для совместного владения клиентом.
 */
class OllamaModelsClient internal constructor(private val http: HttpClient) : AutoCloseable {
    constructor() : this(createHttpClient())

    /**
     * Возвращает отсортированные уникальные имена только локальных completion-моделей.
     *
     * Cloud-записи отбрасываются по `remote_model` и `remote_host` до дополнительных запросов.
     * Если `/api/tags` уже содержит capabilities, они считаются достаточными; для старых версий
     * Ollama или старых записей без этого поля назначение модели уточняется через `/api/show`.
     * Embedding-модели в результат не входят, поскольку не способны выполнить prompt суммаризации.
     */
    suspend fun availableModels(): List<String> {
        val response = http.get("$BASE_URL/api/tags")
        check(response.status.isSuccess()) { "Ollama недоступна (${response.status.value})" }

        val candidates = response.body<OllamaTagsResponse>().models
            .asSequence()
            // Любой из remote-признаков означает прокси-модель, зависящую от внешнего сервиса.
            .filter { it.remoteModel.isNullOrBlank() && it.remoteHost.isNullOrBlank() }
            .mapNotNull { entry ->
                // Новые ответы используют model, но name сохраняет совместимость со старыми
                // локальными версиями Ollama; пустая запись не является выбираемой моделью.
                entry.model.ifBlank { entry.name.orEmpty() }
                    .takeIf(String::isNotBlank)
                    ?.let { it to entry.capabilities }
            }
            .distinctBy { it.first }
            .sortedBy { it.first }
            .toList()
        return buildList {
            for ((model, capabilities) in candidates) {
                // Отдельный /api/show нужен только когда список тегов не сообщает capabilities:
                // это сокращает число запросов и не проверяет уже известные embedding-модели.
                val completion = capabilities?.contains("completion") ?: supportsCompletion(model)
                if (completion) add(model)
            }
        }
    }

    /** Уточняет через подробное описание модели наличие capability `completion`. */
    private suspend fun supportsCompletion(model: String): Boolean {
        val response = http.post("$BASE_URL/api/show") {
            contentType(ContentType.Application.Json)
            setBody(OllamaShowRequest(model))
        }
        check(response.status.isSuccess()) { "Ollama не описала модель $model (${response.status.value})" }
        return response.body<OllamaShowResponse>().capabilities
            .orEmpty()
            .any { it == "completion" }
    }

    /** Закрывает принадлежащий экземпляру HTTP-клиент и освобождает его сетевые ресурсы. */
    override fun close() = http.close()

    private companion object {
        const val BASE_URL = "http://localhost:11434"

        fun createHttpClient() = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 3_000
                socketTimeoutMillis = 30_000
            }
            expectSuccess = false
        }
    }
}

@Serializable
private data class OllamaTagsResponse(val models: List<OllamaModelEntry> = emptyList())

@Serializable
private data class OllamaModelEntry(
    val model: String = "",
    val name: String? = null,
    @SerialName("remote_model") val remoteModel: String? = null,
    @SerialName("remote_host") val remoteHost: String? = null,
    val capabilities: List<String>? = null,
)

@Serializable
private data class OllamaShowRequest(val model: String)

@Serializable
private data class OllamaShowResponse(val capabilities: List<String>? = null)
