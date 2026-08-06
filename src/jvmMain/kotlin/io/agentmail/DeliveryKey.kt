package io.agentmail

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

object DeliveryKey {
    fun profile(settings: AppSettings, telegramBotToken: String): String = sha256(
        listOf(
            "profile:v2",
            settings.imapHost.lowercase(Locale.ROOT),
            settings.imapPort.toString(),
            settings.imapUsername,
            settings.folder,
            settings.telegramChatId,
            telegramBotToken.substringBefore(':').takeIf { it.all(Char::isDigit) }.orEmpty(),
        ).joinToString("\u0000")
    )

    fun email(message: MailMessage): String {
        val canonicalMessageId = message.messageId?.canonicalMessageId()?.takeIf(String::isNotBlank)
        if (canonicalMessageId != null) return "mid:v1:${sha256(canonicalMessageId)}"

        val fingerprint = listOf(
            normalize(message.from).lowercase(Locale.ROOT),
            normalize(message.subject),
            message.receivedAt?.toEpochMilli()?.toString().orEmpty(),
            sha256(normalize(message.body)),
        ).joinToString("\u0000")
        return "fp:v1:${sha256(fingerprint)}"
    }

    private fun String.canonicalMessageId(): String {
        val unfolded = replace(Regex("\\s+"), "").removeSurrounding("<", ">")
        val separator = unfolded.lastIndexOf('@')
        return if (separator < 0) unfolded else {
            unfolded.substring(0, separator + 1) + unfolded.substring(separator + 1).lowercase(Locale.ROOT)
        }
    }

    private fun normalize(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFKC)
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
