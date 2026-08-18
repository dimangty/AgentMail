package io.agentmail

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Контракт автоматической проверки задачи после уведомления о слиянии merge request. */
interface GitLabIssueReviewer {
    /**
     * Проверяет фактическое состояние merge request и обновляет связанную задачу.
     *
     * Возвращает IID обновлённой задачи или `null`, если merge request не слит либо
     * source branch не содержит однозначной ссылки на задачу.
     */
    suspend fun reviewMergedIssue(baseUrl: String, token: String, ref: GitLabMergeRequestRef): Int?
}

/**
 * Клиент минимального набора GitLab API, необходимого автоматизации задач.
 *
 * Access token отправляется только в заголовке `PRIVATE-TOKEN`. Штатный HTTP-клиент
 * не следует redirects: ответ перенаправления считается ошибкой, чтобы секрет не
 * мог уйти на origin из `Location`. Переданный во внутренний конструктор [http]
 * должен сохранять тот же инвариант в production-коде; возможность инъекции нужна
 * для детерминированной проверки сформированных запросов.
 */
class GitLabClient internal constructor(private val http: HttpClient) : GitLabIssueReviewer, AutoCloseable {
    /** Создаёт клиент с production-таймаутами, JSON-десериализацией и запретом redirects. */
    constructor() : this(createHttpClient())

    /**
     * Проверяет доступность GitLab API и валидность [token] запросом текущего пользователя.
     * Неуспешный HTTP-статус преобразуется в исключение согласно политике [checkResponse].
     */
    suspend fun test(baseUrl: String, token: String) {
        val response = http.get("${baseUrl.trimEnd('/')}/api/v4/user") {
            header(PRIVATE_TOKEN, token)
        }
        checkResponse(response.status.value)
    }

    /**
     * Добавляет [labels] к задаче из [issueUrl] и возвращает её IID.
     *
     * URL валидируется относительно [baseUrl] до отправки секрета. Project path
     * кодируется как одна часть URL, поэтому `/` между namespace не превращаются
     * в сегменты API-маршрута. В форме намеренно используется только `add_labels`:
     * уже назначенные метки сохраняются, `remove_labels` этот контракт не посылает.
     * Запятые в названиях запрещены, поскольку GitLab трактует их как разделители.
     */
    suspend fun addIssueLabels(
        baseUrl: String,
        token: String,
        issueUrl: String,
        labels: List<String>,
    ): Int {
        val ref = requireNotNull(GitLabIssueUrlParser.parse(issueUrl, baseUrl)) {
            "Ссылка должна вести на задачу в настроенном GitLab"
        }
        require(token.isNotBlank()) { "GitLab access token не сохранён" }
        val normalizedLabels = labels.map(String::trim).filter(String::isNotEmpty).distinct()
        require(normalizedLabels.isNotEmpty()) { "Выберите хотя бы одну метку" }
        require(normalizedLabels.none { ',' in it }) { "Название метки не должно содержать запятую" }

        val canonicalBase = checkNotNull(baseUrl.canonicalGitLabOrigin())
        // GitLab API принимает `namespace/project` как один percent-encoded идентификатор.
        val project = ref.projectPath.encodeURLPathPart()
        val response = http.put("$canonicalBase/api/v4/projects/$project/issues/${ref.issueIid}") {
            header(PRIVATE_TOKEN, token)
            setBody(FormDataContent(Parameters.build {
                append("add_labels", normalizedLabels.joinToString(","))
            }))
        }
        checkResponse(response.status.value)
        return ref.issueIid
    }

    override suspend fun reviewMergedIssue(
        baseUrl: String,
        token: String,
        ref: GitLabMergeRequestRef,
    ): Int? {
        // Кодирование не даёт данным из ссылки изменить структуру API-маршрута.
        val project = ref.projectPath.encodeURLPathPart()
        val apiRoot = "${baseUrl.trimEnd('/')}/api/v4/projects/$project"
        val mergeRequestResponse = http.get("$apiRoot/merge_requests/${ref.mergeRequestIid}") {
            header(PRIVATE_TOKEN, token)
        }
        checkResponse(mergeRequestResponse.status.value)
        val mergeRequest = mergeRequestResponse.body<GitLabMergeRequest>()
        if (!mergeRequest.state.equals("merged", ignoreCase = true)) return null
        val issueIid = GitLabIssueBranchParser.issueIid(mergeRequest.sourceBranch) ?: return null

        val issueResponse = http.put("$apiRoot/issues/$issueIid") {
            header(PRIVATE_TOKEN, token)
            setBody(FormDataContent(Parameters.build {
                append("add_labels", REVIEWED_LABEL)
                append("remove_labels", MERGE_REQUEST_LABEL)
            }))
        }
        checkResponse(issueResponse.status.value)
        return issueIid
    }

    /** Освобождает ресурсы базового HTTP-клиента. После вызова экземпляр не используется. */
    override fun close() = http.close()

    /**
     * Отделяет постоянные ошибки конфигурации/доступа от временных сбоев GitLab.
     * Redirect также не считается успехом и не выполняется повторно по `Location`.
     */
    private fun checkResponse(status: Int) {
        if (status in 200..299) return
        val message = "GitLab API request failed ($status)"
        if (status in listOf(400, 401, 403, 404)) throw PermanentConfigurationException(message)
        error(message)
    }

    private companion object {
        const val PRIVATE_TOKEN = "PRIVATE-TOKEN"
        const val REVIEWED_LABEL = "Reviewed"
        const val MERGE_REQUEST_LABEL = "Merge Request"

        /** Создаёт клиент с ограниченными ожиданиями и без автоматической передачи токена по redirect. */
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
            followRedirects = false
        }
    }
}

@Serializable
/** Минимальная доверяемая часть ответа GitLab; остальные поля намеренно игнорируются. */
private data class GitLabMergeRequest(
    val state: String,
    @SerialName("source_branch") val sourceBranch: String,
)
