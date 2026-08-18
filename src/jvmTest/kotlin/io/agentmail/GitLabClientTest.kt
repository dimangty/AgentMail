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

/**
 * Контрактные тесты HTTP-границы GitLabClient на MockEngine.
 *
 * Они проверяют не только маршруты и ответы, но и security-инварианты запроса:
 * кодирование project path как одного параметра, передачу токена только заголовком,
 * отсутствие удаления меток и запрет следования redirects с секретом.
 */
class GitLabClientTest {
    @Test
    fun `maps trusted work item URL to issue labels API`() = runTest {
        var requests = 0
        val engine = MockEngine { request ->
            requests++
            assertEquals(HttpMethod.Put, request.method)
            assertEquals("gitlab.elementpay.io", request.url.host)
            assertEquals("/api/v4/projects/casheers%2Fios-client/issues/1331", request.url.encodedPath)
            assertEquals("secret-token", request.headers["PRIVATE-TOKEN"])
            val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            // Ручная операция должна быть аддитивной: отсутствие remove_labels защищает
            // метки, которые уже назначены задаче другими процессами или пользователями.
            assertTrue(body.contains("add_labels=env%3Adev%2Cenv%3Aprod%2Cenv%3Asbox"), body)
            assertFalse(body.contains("remove_labels"), body)
            respond("{}", HttpStatusCode.OK)
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            expectSuccess = false
            followRedirects = false
        }

        GitLabClient(http).use { client ->
            assertEquals(
                1331,
                client.addIssueLabels(
                    baseUrl = "https://gitlab.elementpay.io",
                    token = "secret-token",
                    issueUrl = "https://gitlab.elementpay.io/casheers/ios-client/-/work_items/1331",
                    labels = listOf("env:dev", "env:prod", "env:sbox"),
                ),
            )
        }
        assertEquals(1, requests)
    }

    @Test
    fun `adds selected labels to trusted issue without removing existing labels`() = runTest {
        var requests = 0
        val engine = MockEngine { request ->
            requests++
            assertEquals(HttpMethod.Put, request.method)
            assertEquals("/api/v4/projects/group%2Fproject/issues/123", request.url.encodedPath)
            assertEquals("secret-token", request.headers["PRIVATE-TOKEN"])
            assertFalse(request.url.toString().contains("secret-token"))
            val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            assertTrue(body.contains("add_labels=env%3Adev%2Cenv%3Aprod"), body)
            assertFalse(body.contains("remove_labels"), body)
            assertFalse(body.contains("secret-token"), body)
            respond("{}", HttpStatusCode.OK)
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            expectSuccess = false
            followRedirects = false
        }

        GitLabClient(http).use { client ->
            assertEquals(
                123,
                client.addIssueLabels(
                    baseUrl = "https://gitlab.example.com",
                    token = "secret-token",
                    issueUrl = "https://gitlab.example.com/group/project/-/issues/123",
                    labels = listOf("env:dev", "env:prod"),
                ),
            )
        }
        assertEquals(1, requests)
    }

    @Test
    fun `rejects unsafe issue URLs before sending token`() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond("{}", HttpStatusCode.OK)
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            expectSuccess = false
        }

        GitLabClient(http).use { client ->
            // Матрица объединяет внешне похожий host и URL, которые не идентифицируют
            // проект. Главный постусловный инвариант ниже: ни один запрос не отправлен.
            listOf(
                "https://gitlab.example.com.attacker.test/group/project/-/issues/123",
                "https://gitlab.example.com/123/-/work_items/5",
                "https://gitlab.example.com/groups/acme/-/work_items/5",
            ).forEach { issueUrl ->
                assertFailsWith<IllegalArgumentException>(issueUrl) {
                    client.addIssueLabels(
                        baseUrl = "https://gitlab.example.com",
                        token = "secret-token",
                        issueUrl = issueUrl,
                        labels = listOf("env:dev"),
                    )
                }
            }
        }
        assertEquals(0, requests)
    }

    @Test
    fun `does not follow redirect while adding issue labels`() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests++
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
            // 3xx является ошибкой, а не вторым запросом к Location: иначе заголовок
            // PRIVATE-TOKEN мог бы быть раскрыт origin, выбранному ответом сервера.
            assertFailsWith<IllegalStateException> {
                client.addIssueLabels(
                    baseUrl = "https://gitlab.example.com",
                    token = "secret-token",
                    issueUrl = "https://gitlab.example.com/group/project/-/issues/123",
                    labels = listOf("env:dev"),
                )
            }
        }
        assertEquals(1, requests)
    }

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
            // Тот же запрет фиксируется отдельно для автоматического review-потока,
            // поскольку он начинает работу с GET и тоже несёт секретный заголовок.
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
