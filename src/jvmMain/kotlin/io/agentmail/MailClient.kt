package io.agentmail

import jakarta.mail.BodyPart
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.UIDFolder
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.MimeUtility
import jakarta.mail.search.ComparisonTerm
import jakarta.mail.search.ReceivedDateTerm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.util.Properties
import java.util.Date
import java.nio.charset.Charset

/**
 * Результат одного опроса IMAP. [highestUid] задаёт вычисленную границу опроса,
 * [initialized] обозначает первый запуск, а [failedUid] — первое неразобранное письмо.
 * Вызывающий код должен обработать письма и ошибочный UID до сохранения границы.
 */
data class MailPollResult(
    val messages: List<MailMessage>,
    val highestUid: Long,
    val uidValidity: Long,
    val initialized: Boolean,
    val failedUid: Long? = null,
)

/** Читает новые письма из IMAP, не изменяя состояние почтового ящика. */
class ImapMailClient {
    /**
     * Возвращает не более ста новых писем. UID сравниваются только внутри одного
     * `UIDVALIDITY`; после его смены используется небольшой временной перехлёст.
     */
    suspend fun poll(settings: AppSettings, password: String, cursor: MailCursor): MailPollResult =
        withContext(Dispatchers.IO) {
            val protocol = if (settings.useStartTls) "imap" else "imaps"
            val properties = Properties().apply {
                put("mail.store.protocol", protocol)
                put("mail.$protocol.connectiontimeout", "15000")
                put("mail.$protocol.timeout", "30000")
                put("mail.$protocol.writetimeout", "30000")
                put("mail.$protocol.ssl.checkserveridentity", "true")
                if (settings.useStartTls) {
                    put("mail.imap.starttls.enable", "true")
                    put("mail.imap.starttls.required", "true")
                }
            }
            val store = Session.getInstance(properties).getStore(protocol)
            var folder: Folder? = null
            try {
                store.connect(settings.imapHost, settings.imapPort, settings.imapUsername, password)
                folder = store.getFolder(settings.folder)
                require(folder.exists()) { "IMAP folder '${settings.folder}' не существует" }
                folder.open(Folder.READ_ONLY)
                val uidFolder = folder as? UIDFolder
                    ?: error("IMAP server не поддерживает UID")
                val highestOnServer = folder.messageCount
                    .takeIf { it > 0 }
                    ?.let(folder::getMessage)
                    ?.let(uidFolder::getUID)
                    ?: 0L
                val uidValidity = uidFolder.uidValidity

                // Нулевой UIDVALIDITY означает первый запуск: начинаем с конца и не пересылаем историю.
                if (cursor.uidValidity == 0L) {
                    return@withContext MailPollResult(
                        emptyList(),
                        highestOnServer,
                        uidValidity,
                        initialized = true,
                    )
                }

                val uidValidityChanged = cursor.uidValidity != uidValidity
                val rawMessages = if (uidValidityChanged) {
                    // После сброса UID ищем по дате с перехлёстом, чтобы не потерять пограничное письмо.
                    val since = Date((cursor.checkedAtEpochMillis - RESET_LOOKBACK_MS).coerceAtLeast(0L))
                    folder.search(ReceivedDateTerm(ComparisonTerm.GE, since))
                        .asList()
                        .filter { it.receivedDate?.time?.let { time -> time >= since.time } == true }
                } else {
                    uidFolder.getMessagesByUID(cursor.lastUid + 1, UIDFolder.LASTUID).asList()
                }
                    .asSequence()
                    .filterNotNull()
                    .take(MAX_BATCH)
                    .toList()
                val fetched = mutableListOf<MailMessage>()
                var failedUid: Long? = null
                for (raw in rawMessages) {
                    val uid = uidFolder.getUID(raw)
                    try {
                        fetched += raw.toMailMessage(uid)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        // Останавливаемся на первом сбое, иначе курсор мог бы перепрыгнуть письмо.
                        failedUid = uid
                        break
                    }
                }
                MailPollResult(
                    messages = fetched,
                    // При ошибке продвигаемся только до последнего успешно разобранного UID.
                    highestUid = when {
                        failedUid != null -> fetched.lastOrNull()?.uid ?: cursor.lastUid
                        rawMessages.isNotEmpty() -> uidFolder.getUID(rawMessages.last())
                        else -> highestOnServer
                    },
                    uidValidity = uidValidity,
                    initialized = false,
                    failedUid = failedUid,
                )
            } finally {
                runCatching { if (folder?.isOpen == true) folder.close(false) }
                runCatching { if (store.isConnected) store.close() }
            }
        }

    /** Проверяет подключение и доступ к папке, не загружая старые письма. */
    suspend fun test(settings: AppSettings, password: String) = withContext(Dispatchers.IO) {
        poll(settings, password, MailCursor())
    }

    private fun Message.toMailMessage(uid: Long): MailMessage = MailMessage(
        uid = uid,
        messageId = getHeader("Message-ID")?.firstOrNull()?.let(::decodeHeader),
        from = from?.joinToString { it.toString() }?.let(::decodeHeader).orEmpty(),
        subject = subject?.let(::decodeHeader).orEmpty(),
        body = extractText(this).take(MAX_BODY_CHARS),
        receivedAt = receivedDate?.toInstant() ?: sentDate?.toInstant(),
    )

    private fun extractText(part: Part): String {
        if (Part.ATTACHMENT.equals(part.disposition, ignoreCase = true) || part.fileName != null) return ""
        return when {
            part.isMimeType("text/plain") -> readBoundedText(part)
            part.isMimeType("text/html") -> Jsoup.parse(readBoundedText(part)).text()
            part.isMimeType("multipart/*") -> extractMultipart(part.content as Multipart)
            part.isMimeType("message/rfc822") -> ""
            else -> ""
        }
    }

    private fun extractMultipart(multipart: Multipart): String {
        val parts = (0 until multipart.count).map { multipart.getBodyPart(it) }
        if (multipart.contentType.startsWith("multipart/alternative", ignoreCase = true)) {
            // В альтернативных представлениях предпочитаем обычный текст перед HTML.
            return parts.firstText("text/plain") ?: parts.firstText("text/html").orEmpty()
        }
        return parts.asSequence()
            .filterNot { Part.ATTACHMENT.equals(it.disposition, ignoreCase = true) }
            .filterNot { it.fileName != null }
            .map(::extractText)
            .filter(String::isNotBlank)
            .joinToString("\n")
            .take(MAX_BODY_CHARS)
    }

    private fun List<BodyPart>.firstText(mimeType: String): String? =
        firstOrNull { it.isMimeType(mimeType) }?.let(::extractText)?.takeIf(String::isNotBlank)

    private fun decodeHeader(value: String): String = runCatching { MimeUtility.decodeText(value) }.getOrDefault(value)

    private fun readBoundedText(part: Part): String {
        val charsetName = runCatching { ContentType(part.contentType).getParameter("charset") }.getOrNull()
        val charset = runCatching { Charset.forName(charsetName ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
        return part.inputStream.reader(charset).buffered().use { reader ->
            // Ограничиваем чтение потока, чтобы большой MIME-part не занимал память целиком.
            val output = StringBuilder()
            val buffer = CharArray(4_096)
            while (output.length < MAX_BODY_CHARS) {
                val read = reader.read(buffer, 0, minOf(buffer.size, MAX_BODY_CHARS - output.length))
                if (read < 0) break
                output.append(buffer, 0, read)
            }
            output.toString()
        }
    }

    private companion object {
        const val MAX_BATCH = 100
        const val MAX_BODY_CHARS = 200_000
        const val RESET_LOOKBACK_MS = 5 * 60_000L
    }
}
