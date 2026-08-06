package io.agentmail

import java.text.Normalizer
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Ищет точное упоминание пользовательского тега в почтовом тексте.
 *
 * Сравнение приводит Unicode к форме NFKC и выполняется без учёта регистра,
 * чтобы визуально эквивалентные варианты обрабатывались одинаково. При этом
 * совпадение внутри более длинного handle не считается упоминанием.
 */
object TagMatcher {
    /**
     * Возвращает `true`, если [text] содержит отдельное упоминание [tag].
     *
     * Буквы и цифры любого Unicode-алфавита, а также `.`, `_` и `-` считаются
     * продолжением handle с обеих сторон. Пустой или состоящий из пробелов тег
     * никогда не совпадает.
     */
    fun contains(text: String, tag: String): Boolean {
        if (tag.isBlank()) return false
        val normalizedText = normalize(text)
        val normalizedTag = Regex.escape(normalize(tag))
        return Regex("(?<![\\p{L}\\p{N}._-])$normalizedTag(?![\\p{L}\\p{N}._-])").containsMatchIn(normalizedText)
    }

    /**
     * Ищет [tag] сначала в теме, затем в текстовом теле [message].
     * Совпадения в метаданных отправителя или иных MIME-частях не учитываются.
     */
    fun matches(message: MailMessage, tag: String): Boolean =
        contains(message.subject, tag) || contains(message.body, tag)

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
}

/**
 * Формирует HTML-уведомление для Telegram из недоверенных данных письма и модели.
 *
 * Все динамические фрагменты экранируются до включения в разметку и ограничиваются
 * по длине так, чтобы итоговое сообщение укладывалось в лимит Telegram. Пустой ответ
 * модели заменяется локальным фрагментом тела письма.
 */
object TelegramMessageFormatter {
    private val dateFormat = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())

    /**
     * Собирает готовое HTML-уведомление об упоминании [tag] в [message].
     *
     * Значения отправителя, темы, даты и [summary] рассматриваются как недоверенные:
     * HTML в них не интерпретируется Telegram. Если [summary] пуст после удаления
     * внешних пробелов, вместо него используется [localExcerpt].
     */
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

    /**
     * Возвращает локальный фрагмент тела письма для резервного уведомления.
     * Последовательности пробельных символов сворачиваются, результат ограничивается
     * 700 символами, а полностью пустое тело заменяется поясняющим текстом.
     */
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
