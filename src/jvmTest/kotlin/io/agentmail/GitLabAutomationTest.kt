package io.agentmail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitLabAutomationTest {
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
