package io.agentmail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Проверяет позитивные форматы GitLab и fail-closed границу парсеров.
 *
 * Security-сценарии сгруппированы в матрицы, чтобы одна проверка фиксировала классы
 * атак, а не отдельный пример: подмена origin, неоднозначный authority, скрытые
 * разделители rawPath и расхождение независимых признаков уведомления.
 */
class GitLabAutomationTest {
    @Test
    fun `parses trusted GitLab work item URL`() {
        assertEquals(
            GitLabIssueRef("casheers/ios-client", 1331),
            GitLabIssueUrlParser.parse(
                "https://gitlab.elementpay.io/casheers/ios-client/-/work_items/1331",
                "https://gitlab.elementpay.io",
            ),
        )
    }

    @Test
    fun `parses trusted GitLab issue URL with nested project path`() {
        assertEquals(
            GitLabIssueRef("group/platform/project", 123),
            GitLabIssueUrlParser.parse(
                "https://gitlab.example.com:443/group/platform/project/-/issues/123/",
                "https://gitlab.example.com",
            ),
        )
    }

    @Test
    fun `rejects untrusted and malformed GitLab issue URLs`() {
        val baseUrl = "https://gitlab.example.com"
        // Матрица покрывает подмену хоста/схемы/порта, user info, лишние компоненты,
        // неверный тип ресурса и обход структуры project path. Любая строка обязана
        // завершиться `null`, иначе вызывающий код может отправить токен не туда.
        listOf(
            "https://gitlab.example.com.attacker.test/group/project/-/issues/1",
            "https://gitlab.example.com.attacker.test/group/project/-/work_items/1",
            "http://gitlab.example.com/group/project/-/issues/1",
            "https://gitlab.example.com:8443/group/project/-/issues/1",
            "https://gitlab.example.com:/group/project/-/issues/1",
            "https://user@gitlab.example.com/group/project/-/issues/1",
            "https://gitlab.example.com/group/project/-/issues/1?token=x",
            "https://gitlab.example.com/group/project/-/issues/1#note",
            "https://gitlab.example.com/group/project/-/merge_requests/1",
            "https://gitlab.example.com/group/project/-/issues/0",
            "https://gitlab.example.com/123/-/work_items/5",
            "https://gitlab.example.com/groups/acme/-/work_items/5",
            "https://gitlab.example.com/group%2Fproject/-/issues/1",
            "https://gitlab.example.com/group/../project/-/issues/1",
        ).forEach { issueUrl ->
            assertNull(GitLabIssueUrlParser.parse(issueUrl, baseUrl), issueUrl)
        }
    }

    @Test
    fun `parses live GitLab notification with merge line in body`() {
        val message = MailMessage(
            uid = 1,
            from = "gitlab@elementpay.io",
            subject = "Test",
            body = """

                Merge request !2 was merged

                Branches: features/issue-1 to master
            """.trimIndent(),
            receivedAt = null,
            links = listOf(
                MailLink("View it on GitLab.", "https://gitlab.elementpay.io/group/project/-/merge_requests/2")
            ),
        )

        assertEquals(
            GitLabMergeRequestRef("group/project", 2),
            GitLabMergeNotificationParser.parse(message, "https://gitlab.elementpay.io"),
        )
        assertEquals(1, GitLabIssueBranchParser.issueIid("features/issue-1"))
    }

    @Test
    fun `parses trusted merged notification and issue branch`() {
        val message = MailMessage(
            uid = 1,
            from = "gitlab@example.com",
            subject = "Merge request !138 was merged",
            body = "Branches: feature/issue-233-2 to develop",
            receivedAt = null,
            links = listOf(MailLink("View it on GitLab.", "https://gitlab.example.com/group/project/-/merge_requests/138")),
        )

        assertEquals(
            GitLabMergeRequestRef("group/project", 138),
            GitLabMergeNotificationParser.parse(message, "https://gitlab.example.com"),
        )
        assertEquals(233, GitLabIssueBranchParser.issueIid("feature/issue-233-2"))
    }

    @Test
    fun `rejects unrelated text and untrusted origins`() {
        val base = MailMessage(
            uid = 1,
            from = "sender@example.com",
            subject = "Merge request !138 was opened",
            body = "Something else was merged",
            receivedAt = null,
            links = listOf(MailLink("View it on GitLab", "https://gitlab.example.com/group/project/-/merge_requests/138")),
        )
        assertNull(GitLabMergeNotificationParser.parse(base, "https://gitlab.example.com"))
        assertNull(
            GitLabMergeNotificationParser.parse(
                base.copy(subject = "Merge request !138 was merged", links = listOf(
                    MailLink("View it on GitLab", "https://gitlab.example.com.attacker.test/group/project/-/merge_requests/138")
                )),
                "https://gitlab.example.com",
            )
        )
    }

    @Test
    fun `rejects mismatched merge request iid and unrelated branch`() {
        val message = MailMessage(
            uid = 1,
            from = "gitlab@example.com",
            subject = "Merge request !138 was merged",
            body = "",
            receivedAt = null,
            links = listOf(MailLink("View it on GitLab", "https://gitlab.example.com/group/project/-/merge_requests/139")),
        )
        assertNull(GitLabMergeNotificationParser.parse(message, "https://gitlab.example.com"))
        assertNull(GitLabIssueBranchParser.issueIid("feature/task-233"))
        assertNull(GitLabIssueBranchParser.issueIid("issue-999/feature/issue-233-2"))
    }

    @Test
    fun `rejects merged phrase found only in forwarded body`() {
        val message = MailMessage(
            uid = 1,
            from = "sender@example.com",
            subject = "Fwd: release status",
            body = "Quoted: Merge request !138 was merged",
            receivedAt = null,
            links = listOf(MailLink("View it on GitLab", "https://gitlab.example.com/group/project/-/merge_requests/138")),
        )

        assertNull(GitLabMergeNotificationParser.parse(message, "https://gitlab.example.com"))
    }

    @Test
    fun `rejects merge line after earlier content or with quote prefix`() {
        val link = listOf(
            MailLink("View it on GitLab", "https://gitlab.example.com/group/project/-/merge_requests/2")
        )
        val base = MailMessage(
            uid = 1,
            from = "sender@example.com",
            subject = "Test",
            body = "Forwarded message\nMerge request !2 was merged",
            receivedAt = null,
            links = link,
        )

        // Матрица подтверждает, что признаки не комбинируются из пересланного текста:
        // merge-фраза должна быть первой, не быть цитатой и совпасть с IID ссылки.
        assertNull(GitLabMergeNotificationParser.parse(base, "https://gitlab.example.com"))
        assertNull(
            GitLabMergeNotificationParser.parse(
                base.copy(body = "> Merge request !2 was merged"),
                "https://gitlab.example.com",
            )
        )
        assertNull(
            GitLabMergeNotificationParser.parse(
                base.copy(
                    body = "Merge request !3 was merged",
                    links = link,
                ),
                "https://gitlab.example.com",
            )
        )
    }
}
