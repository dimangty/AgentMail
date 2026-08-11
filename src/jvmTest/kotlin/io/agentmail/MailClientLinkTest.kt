package io.agentmail

import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMultipart
import kotlin.test.Test
import kotlin.test.assertEquals

class MailClientLinkTest {
    @Test
    fun `preserves GitLab link from html alternative`() {
        val multipart = MimeMultipart("alternative").apply {
            addBodyPart(MimeBodyPart().apply { setText("Merge request !138 was merged") })
            addBodyPart(MimeBodyPart().apply {
                setContent(
                    """<p>Merge request !138 was merged</p><a href="https://gitlab.example.com/g/p/-/merge_requests/138">View it on GitLab</a>""",
                    "text/html; charset=UTF-8",
                )
                setHeader("Content-Type", "text/html; charset=UTF-8")
            })
        }
        val root = MimeBodyPart().apply {
            setContent(multipart)
            setHeader("Content-Type", multipart.contentType)
        }

        assertEquals(
            listOf(MailLink("View it on GitLab", "https://gitlab.example.com/g/p/-/merge_requests/138")),
            ImapMailClient().extractLinks(root),
        )
    }
}
