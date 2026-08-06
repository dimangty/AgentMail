package io.agentmail

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * Строит стабильные хешированные идентификаторы области доставки и отдельного письма.
 *
 * Ключи не содержат исходные значения в открытом виде, а во вход хеширования
 * включён маркер версии. Они предназначены для дедупликации, а не для
 * криптографической аутентификации или шифрования.
 */
object DeliveryKey {
    /**
     * Возвращает ключ области, внутри которой письмо считается уже доставленным.
     *
     * Область включает IMAP endpoint и пользователя, папку, Telegram-чат и числовой
     * идентификатор бота. Поэтому смена любого участника маршрута создаёт независимую
     * историю. Секретная часть [telegramBotToken] намеренно не попадает даже во вход
     * хеширования; токен нестандартного формата не добавляет идентификатор бота.
     */
    fun profile(settings: AppSettings, telegramBotToken: String): String = sha256(
        listOf(
            "profile:v2",
            settings.imapHost.lowercase(Locale.ROOT),
            settings.imapPort.toString(),
            settings.imapUsername,
            settings.folder,
            settings.telegramChatId,
            telegramBotToken.substringBefore(':').takeIf { it.all(Char::isDigit) }.orEmpty(),
            // Нулевой символ исключает неоднозначность при склеивании полей разной длины.
        ).joinToString("\u0000")
    )

    /**
     * Возвращает долговечный ключ письма, не зависящий от его положения в IMAP.
     *
     * Непустой `Message-ID` имеет приоритет и канонизируется с сохранением регистра
     * локальной части и приведением домена к нижнему регистру. При его отсутствии
     * ключ строится из нормализованных отправителя, темы, времени и хеша тела.
     * IMAP UID не входит ни в один вариант, чтобы ключ переживал смену `UIDVALIDITY`.
     */
    fun email(message: MailMessage): String {
        val canonicalMessageId = message.messageId?.canonicalMessageId()?.takeIf(String::isNotBlank)
        if (canonicalMessageId != null) return "mid:v1:${sha256(canonicalMessageId)}"

        // Тело хешируется отдельно, чтобы не хранить его в составной строке отпечатка.
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
        // Регистр локальной части сохраняется, доменная часть нечувствительна к регистру.
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
