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

interface GitLabIssueReviewer {
    suspend fun reviewMergedIssue(baseUrl: String, token: String, ref: GitLabMergeRequestRef): Int?
}

class GitLabClient internal constructor(private val http: HttpClient) : GitLabIssueReviewer, AutoCloseable {
    constructor() : this(createHttpClient())

    suspend fun test(baseUrl: String, token: String) {
        val response = http.get("${baseUrl.trimEnd('/')}/api/v4/user") {
            header(PRIVATE_TOKEN, token)
        }
        checkResponse(response.status.value)
    }

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

    override fun close() = http.close()

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
private data class GitLabMergeRequest(
    val state: String,
    @SerialName("source_branch") val sourceBranch: String,
)
