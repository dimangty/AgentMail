package io.agentmail

import java.text.Normalizer
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object TagMatcher {
    fun contains(text: String, tag: String): Boolean {
        if (tag.isBlank()) return false
        val normalizedText = normalize(text)
        val normalizedTag = Regex.escape(normalize(tag))
        return Regex("(?<![\\p{L}\\p{N}._-])$normalizedTag(?![\\p{L}\\p{N}._-])").containsMatchIn(normalizedText)
    }

    fun matches(message: MailMessage, tag: String): Boolean =
        contains(message.subject, tag) || contains(message.body, tag)

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
}

object TelegramMessageFormatter {
    private val dateFormat = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())

    fun format(message: MailMessage, tag: String, summary: String): String {
        val date = message.receivedAt?.let(dateFormat::format) ?: "неизвестно"
        val safeSummary = summary.trim().ifBlank { localExcerpt(message) }
        return buildString {
            append("<b>Вас упомянули: ").append(escapeAndLimit(tag, 150)).append("</b>\n\n")
            append("<b>От:</b> ").append(escapeAndLimit(message.from.ifBlank { "неизвестно" }, 500)).append('\n')
            append("<b>Тема:</b> ").append(escapeAndLimit(message.subject.ifBlank { "без темы" }, 500)).append('\n')
            append("<b>Дата:</b> ").append(escapeAndLimit(date, 100)).append("\n\n")
            append("<b>Кратко:</b> ").append(escapeAndLimit(safeSummary, 2_600))
        }
    }

    fun localExcerpt(message: MailMessage): String = message.body
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(700)
        .ifBlank { "Текст письма отсутствует." }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun escapeAndLimit(value: String, limit: Int): String {
        val escaped = escape(value)
        if (escaped.length <= limit) return escaped
        val truncated = escaped.take(limit - 1).replace(Regex("&[^;]*$"), "")
        return "$truncated…"
    }

}
