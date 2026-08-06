package io.agentmail

import java.text.Normalizer
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Ищет точное упоминание тега с учётом Unicode и границ handle. */
object TagMatcher {
    /**
     * Проверяет наличие тега без учёта регистра. Буквы, цифры, `.`, `_` и `-`
     * считаются продолжением handle, поэтому совпадение внутри длинного тега запрещено.
     */
    fun contains(text: String, tag: String): Boolean {
        if (tag.isBlank()) return false
        val normalizedText = normalize(text)
        val normalizedTag = Regex.escape(normalize(tag))
        return Regex("(?<![\\p{L}\\p{N}._-])$normalizedTag(?![\\p{L}\\p{N}._-])").containsMatchIn(normalizedText)
    }

    /** Ищет тег в теме и текстовом теле письма. */
    fun matches(message: MailMessage, tag: String): Boolean =
        contains(message.subject, tag) || contains(message.body, tag)

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
}

/** Формирует ограниченный по длине и безопасный HTML для Telegram. */
object TelegramMessageFormatter {
    private val dateFormat = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())

    /** Собирает уведомление, экранируя все данные письма и результат модели. */
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

    /** Возвращает локальный фрагмент письма, если модель недоступна или ответ пуст. */
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
        // После усечения удаляем незавершённую HTML entity, чтобы не сломать разметку.
        val truncated = escaped.take(limit - 1).replace(Regex("&[^;]*$"), "")
        return "$truncated…"
    }

}
