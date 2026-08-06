package io.agentmail

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DeliveryKeyTest {
    @Test
    fun `message id key is stable across uid changes`() {
        val first = message(uid = 10, messageId = "<Order-42@EXAMPLE.COM>")
        val moved = message(uid = 999, messageId = " Order-42@example.com ")

        assertEquals(DeliveryKey.email(first), DeliveryKey.email(moved))
    }

    @Test
    fun `fallback key changes with meaningful content`() {
        val first = message(uid = 1, messageId = null, body = "Approve invoice A")
        val second = message(uid = 2, messageId = null, body = "Approve invoice B")

        assertNotEquals(DeliveryKey.email(first), DeliveryKey.email(second))
    }

    @Test
    fun `fallback is stable across uid validity reset`() {
        val first = message(uid = 1, messageId = null)
        val moved = message(uid = 500, messageId = null)

        assertEquals(DeliveryKey.email(first), DeliveryKey.email(moved))
    }

    private fun message(
        uid: Long,
        messageId: String?,
        body: String = "Body",
    ) = MailMessage(
        uid = uid,
        messageId = messageId,
        from = "sender@example.com",
        subject = "Subject",
        body = body,
        receivedAt = Instant.parse("2026-08-06T10:00:00Z"),
    )
}
