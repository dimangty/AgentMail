package io.agentmail

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramMessageFormatterTest {
    @Test
    fun `escapes untrusted html and stays within Telegram limit`() {
        val message = MailMessage(
            uid = 1,
            from = "<script>alert(1)</script>",
            subject = "A & B",
            body = "body",
            receivedAt = Instant.EPOCH,
        )

        val result = TelegramMessageFormatter.format(message, "@me", "<b>${"x".repeat(5000)}</b>")

        assertFalse(result.contains("<script>"))
        assertTrue(result.contains("&lt;script&gt;"))
        assertTrue(result.length <= 4096)
    }
}
