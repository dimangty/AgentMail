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
 * Снимок результата одного опроса IMAP-папки и безопасная следующая позиция курсора.
 *
 * UID имеет смысл только в пределах поколения папки, заданного [uidValidity]. Поэтому
 * [highestUid] нельзя сохранять отдельно от [uidValidity]: после смены поколения сервер
 * вправе повторно использовать те же числовые UID для других писем.
 *
 * @property messages письма, успешно разобранные по порядку до первого сбоя, не включая
 * сбойное письмо.
 * @property highestUid максимальный UID, до которого курсор разрешено безопасно продвинуть.
 * @property uidValidity идентификатор поколения UID, полученный от текущей IMAP-папки.
 * @property initialized признак первого подключения, при котором фиксируется конец папки,
 * но накопленная история намеренно не возвращается.
 * @property failedUid UID первого письма, которое не удалось разобрать; вызывающий код должен
 * оставить возможность повторить его обработку, а не перескочить через него.
 */
data class MailPollResult(
    val messages: List<MailMessage>,
    val highestUid: Long,
    val uidValidity: Long,
    val initialized: Boolean,
    val failedUid: Long? = null,
)

/**
 * Читает новые письма из IMAP, не изменяя состояние почтового ящика.
 *
 * Папка всегда открывается в режиме `READ_ONLY`, а соединение и папка принадлежат одному
 * вызову [poll] и закрываются даже при ошибке разбора MIME или отмене корутины.
 */
class ImapMailClient {
    /**
     * Возвращает не более ста новых писем после [cursor] и вычисляет безопасную границу
     * следующего опроса.
     *
     * При неизменном `UIDVALIDITY` сервер выбирает сообщения по UID, а не по порядковому
     * номеру в папке: номера сообщений меняются при удалениях, UID в пределах поколения — нет.
     * После смены `UIDVALIDITY` прежний UID несопоставим с новым поколением, поэтому поиск
     * временно переключается на дату получения с небольшим перехлёстом. Это восстановление
     * выполняется в режиме best effort: перехлёст снижает риск потери на временной границе,
     * но поиск зависит от серверного `receivedDate`, а устойчивый ключ доставки должен
     * отсеять повторно найденные письма.
     *
     * Если MIME-разбор письма завершается ошибкой, обработка останавливается на его UID,
     * чтобы сохранённый курсор не сделал это письмо недоступным для следующей попытки.
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

                // Нулевой UIDVALIDITY означает отсутствие поколения в курсоре. Запоминаем текущий
                // конец папки, чтобы первый запуск не превратился в массовую пересылку всей истории.
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
                    // После сброса UID прежние числа больше ничего не идентифицируют. Дата с
                    // перехлёстом уменьшает риск потери на границе ценой повторных кандидатов;
                    // письма без серверной даты могут не попасть в такой восстановительный поиск.
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
                        // Последующие UID не обрабатываем: иначе успешное более новое письмо могло бы
                        // продвинуть курсор через сбойное и навсегда исключить его из следующих опросов.
                        failedUid = uid
                        break
                    }
                }
                MailPollResult(
                    messages = fetched,
                    // При ошибке границей служит только последний успешно разобранный UID. Если
                    // сломалось первое письмо, сохраняем исходную позицию курсора без продвижения.
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

    /**
     * Проверяет подключение, аутентификацию, наличие папки и поддержку сервером UID.
     *
     * Пустой курсор включает семантику первого запуска: метод определяет текущую границу,
     * но не загружает и не разбирает накопленные письма.
     */
    suspend fun test(settings: AppSettings, password: String) = withContext(Dispatchers.IO) {
        poll(settings, password, MailCursor())
    }

    /**
     * Преобразует Jakarta Mail-сообщение в ограниченное доменное представление.
     * Заголовки декодируются по MIME-правилам, а тело извлекается потоково и обрезается,
     * чтобы размер отдельного письма не определял потребление памяти приложением.
     */
    private fun Message.toMailMessage(uid: Long): MailMessage = MailMessage(
        uid = uid,
        messageId = getHeader("Message-ID")?.firstOrNull()?.let(::decodeHeader),
        from = from?.joinToString { it.toString() }?.let(::decodeHeader).orEmpty(),
        subject = subject?.let(::decodeHeader).orEmpty(),
        body = extractText(this).take(MAX_BODY_CHARS),
        receivedAt = receivedDate?.toInstant() ?: sentDate?.toInstant(),
        links = extractLinks(this),
    )

    /** Сохраняет ограниченный набор HTML-ссылок, не смешивая их с видимым текстом письма. */
    internal fun extractLinks(part: Part): List<MailLink> {
        if (Part.ATTACHMENT.equals(part.disposition, ignoreCase = true) || part.fileName != null) return emptyList()
        val links = when {
            part.isMimeType("text/html") -> Jsoup.parse(readBoundedText(part)).select("a[href]").map { anchor ->
                MailLink(
                    text = anchor.text().replace(Regex("\\s+"), " ").trim().take(MAX_LINK_CHARS),
                    href = anchor.attr("href").trim().take(MAX_LINK_CHARS),
                )
            }
            part.isMimeType("multipart/*") -> {
                val multipart = part.content as Multipart
                (0 until multipart.count).flatMap { extractLinks(multipart.getBodyPart(it)) }
            }
            else -> emptyList()
        }
        return links.filter { it.text.isNotBlank() && it.href.isNotBlank() }.distinct().take(MAX_LINKS)
    }

    /**
     * Извлекает пользовательский текст из MIME-дерева без содержимого вложений.
     *
     * Наличие имени файла трактуется как вложение даже без корректного `Content-Disposition`.
     * HTML переводится в видимый текст, а вложенные `message/rfc822` не раскрываются: это
     * самостоятельные письма, которые не должны смешиваться с основным телом уведомления.
     */
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

    /**
     * Собирает текст составной MIME-части с учётом семантики `multipart/alternative`.
     *
     * Для альтернатив выбирается одно представление, преимущественно `text/plain`, иначе
     * одинаковое письмо попало бы в итог дважды. Для остальных multipart-контейнеров текстовые
     * дочерние части объединяются, но вложения отбрасываются до рекурсивного чтения.
     */
    private fun extractMultipart(multipart: Multipart): String {
        val parts = (0 until multipart.count).map { multipart.getBodyPart(it) }
        if (multipart.contentType.startsWith("multipart/alternative", ignoreCase = true)) {
            // Альтернативы представляют одно и то же содержание, поэтому выбираем ровно одну:
            // обычный текст предпочтительнее HTML и не требует восстановления разметки.
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

    /**
     * Читает текстовую MIME-часть с заявленной кодировкой, не превышая [MAX_BODY_CHARS].
     * Неизвестная или отсутствующая кодировка заменяется UTF-8, чтобы повреждённый заголовок
     * `Content-Type` не срывал обработку всего письма.
     */
    private fun readBoundedText(part: Part): String {
        val charsetName = runCatching { ContentType(part.contentType).getParameter("charset") }.getOrNull()
        val charset = runCatching { Charset.forName(charsetName ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
        return part.inputStream.reader(charset).buffered().use { reader ->
            // Ограничение применяется во время чтения, а не после создания полной строки:
            // иначе большая MIME-часть всё равно успела бы целиком занять память.
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
        const val MAX_LINKS = 50
        const val MAX_LINK_CHARS = 2_000
        const val RESET_LOOKBACK_MS = 5 * 60_000L
    }
}
