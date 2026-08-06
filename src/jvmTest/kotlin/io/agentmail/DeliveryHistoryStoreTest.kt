package io.agentmail

import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeliveryHistoryStoreTest {
    @Test
    fun `delivered message remains blocked after reopen`() {
        val directory = Files.createTempDirectory("agentmail-history-test")
        val database = directory.resolve("history.db")
        val message = message()

        DeliveryHistoryStore(database).use { store ->
            assertTrue(store.beginAttempt("profile", "email", message, uidValidity = 7))
            store.markDelivered("profile", "email", telegramMessageId = 123)
        }

        DeliveryHistoryStore(database).use { reopened ->
            assertTrue(reopened.isBlocked("profile", "email"))
            val records = reopened.recent("profile")
            assertEquals(1, records.size)
            assertEquals(DeliveryStatus.DELIVERED, records.single().status)
            assertFalse(reopened.beginAttempt("profile", "email", message, uidValidity = 8))
        }
    }

    @Test
    fun `unfinished attempt remains blocked after immediate reopen`() {
        val directory = Files.createTempDirectory("agentmail-history-crash-test")
        val database = directory.resolve("history.db")

        DeliveryHistoryStore(database).use { store ->
            assertTrue(store.beginAttempt("profile", "email", message(), uidValidity = 7))
        }

        DeliveryHistoryStore(database).use { reopened ->
            assertEquals(DeliveryStatus.ATTEMPTING, reopened.recent("profile").single().status)
            assertTrue(reopened.isBlocked("profile", "email"))
        }
    }

    private fun message() = MailMessage(
        uid = 42,
        messageId = "<message@example.com>",
        from = "sender@example.com",
        subject = "Please review",
        body = "Hello @dmitry.bykov",
        receivedAt = Instant.parse("2026-08-06T10:00:00Z"),
    )
}
