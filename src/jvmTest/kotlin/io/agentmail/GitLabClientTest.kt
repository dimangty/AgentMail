package io.agentmail

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GitLabClientTest {
    @Test
    fun `verifies merged request and updates issue labels`() = runTest {
        var requests = 0
        val engine = MockEngine { request ->
            requests++
            assertEquals("secret-token", request.headers["PRIVATE-TOKEN"])
            assertFalse(request.url.toString().contains("secret-token"))
            when (requests) {
                1 -> {
                    assertEquals(HttpMethod.Get, request.method)
                    assertEquals("/api/v4/projects/group%2Fproject/merge_requests/138", request.url.encodedPath)
                    respond(
                        """{"state":"merged","source_branch":"feature/issue-233-2"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                else -> {
                    assertEquals(HttpMethod.Put, request.method)
                    assertEquals("/api/v4/projects/group%2Fproject/issues/233", request.url.encodedPath)
                    val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    assertTrue(body.contains("add_labels=Reviewed"))
                    assertTrue(body.contains("remove_labels=Merge+Request"))
                    assertFalse(body.contains("secret-token"))
                    respond("{}", HttpStatusCode.OK)
                }
            }
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            expectSuccess = false
        }

        GitLabClient(http).use { client ->
            assertEquals(
                233,
                client.reviewMergedIssue(
                    "https://gitlab.example.com",
                    "secret-token",
                    GitLabMergeRequestRef("group/project", 138),
                ),
            )
        }
        assertEquals(2, requests)
    }

    @Test
    fun `does not mutate issue for open merge request`() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond(
                """{"state":"opened","source_branch":"feature/issue-233"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            expectSuccess = false
        }

        GitLabClient(http).use { client ->
            assertEquals(
                null,
                client.reviewMergedIssue(
                    "https://gitlab.example.com",
                    "secret-token",
                    GitLabMergeRequestRef("group/project", 138),
                ),
            )
        }
        assertEquals(1, requests)
    }

    @Test
    fun `does not follow redirects with token`() = runTest {
        var requests = 0
        val engine = MockEngine { request ->
            requests++
            assertEquals("secret-token", request.headers["PRIVATE-TOKEN"])
            respond(
                "",
                HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, "https://attacker.test/steal"),
            )
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            expectSuccess = false
            followRedirects = false
        }

        GitLabClient(http).use { client ->
            assertFailsWith<IllegalStateException> {
                client.reviewMergedIssue(
                    "https://gitlab.example.com",
                    "secret-token",
                    GitLabMergeRequestRef("group/project", 138),
                )
            }
        }
        assertEquals(1, requests)
    }
}
