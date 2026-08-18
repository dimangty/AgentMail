package io.agentmail

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant

/**
 * Устойчивый SQLite-журнал Telegram-доставок и действий над GitLab issues.
 *
 * В обоих журналах уникальность задаётся парой ключей профиля и письма, а резервация
 * перед внешним запросом защищает от повторной обработки. Для Telegram переход имеет
 * вид `ATTEMPTING -> DELIVERED | FAILED | UNKNOWN`: только гарантированный `FAILED`
 * допускает повтор, а неоднозначный `UNKNOWN` блокирует дубликат. GitLab использует
 * `ATTEMPTING -> SUCCEEDED | FAILED`, причём `FAILED` можно зарезервировать заново.
 *
 * Зависшие состояния восстанавливаются намеренно асимметрично. Неизвестно, принял ли
 * Telegram сообщение, поэтому его попытка становится `UNKNOWN`; повторяемое изменение
 * меток GitLab после прерывания становится `FAILED` и может быть безопасно запущено снова.
 * Операции синхронизированы в пределах экземпляра, а WAL и ожидание занятой БД
 * уменьшают конфликты между отдельными подключениями.
 */
class DeliveryHistoryStore(private val databasePath: Path = defaultDatabasePath()) : AutoCloseable {
    private val connection: Connection

    init {
        databasePath.parent?.let(Files::createDirectories)
        connection = DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}")
        connection.createStatement().use { statement ->
            // WAL уменьшает блокировки, FULL усиливает долговечность записи, timeout ждёт конкурирующую транзакцию.
            statement.execute("PRAGMA busy_timeout = 5000")
            statement.execute("PRAGMA journal_mode = WAL")
            statement.execute("PRAGMA synchronous = FULL")
        }
        migrateSchema()
        recoverStaleAttempts()
        recoverStaleGitLabActions()
    }

    /**
     * В одной транзакции доводит схему до [SCHEMA_VERSION], сохраняя существующую
     * Telegram-историю. В частности, база версии 1 получает отдельный журнал GitLab;
     * повторный запуск безопасен благодаря `IF NOT EXISTS`. Более новую неизвестную
     * схему открывать запрещено, чтобы не интерпретировать её данные по старому контракту.
     */
    private fun migrateSchema() {
        val version = connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { result ->
                check(result.next()) { "Cannot read history database version" }
                result.getInt(1)
            }
        }
        require(version <= SCHEMA_VERSION) { "Unsupported history database version $version" }
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS delivery_history (
                        profile_key TEXT NOT NULL,
                        email_key TEXT NOT NULL,
                        sender TEXT NOT NULL,
                        subject TEXT NOT NULL,
                        received_at_ms INTEGER,
                        uid_validity INTEGER NOT NULL,
                        imap_uid INTEGER NOT NULL,
                        status TEXT NOT NULL CHECK (status IN ('ATTEMPTING', 'DELIVERED', 'UNKNOWN', 'FAILED')),
                        telegram_message_id INTEGER,
                        reserved_at_ms INTEGER NOT NULL,
                        delivered_at_ms INTEGER,
                        updated_at_ms INTEGER NOT NULL,
                        last_error TEXT,
                        PRIMARY KEY (profile_key, email_key)
                    )
                    """.trimIndent()
                )
                statement.execute(
                    "CREATE INDEX IF NOT EXISTS delivery_history_recent_idx " +
                        "ON delivery_history(profile_key, updated_at_ms DESC)"
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS gitlab_action_history (
                        profile_key TEXT NOT NULL,
                        email_key TEXT NOT NULL,
                        status TEXT NOT NULL CHECK (status IN ('ATTEMPTING', 'SUCCEEDED', 'FAILED')),
                        reserved_at_ms INTEGER NOT NULL,
                        succeeded_at_ms INTEGER,
                        updated_at_ms INTEGER NOT NULL,
                        last_error TEXT,
                        PRIMARY KEY (profile_key, email_key)
                    )
                    """.trimIndent()
                )
                statement.execute("PRAGMA user_version = $SCHEMA_VERSION")
            }
            connection.commit()
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    /**
     * Проверяет, запрещена ли новая отправка для пары [profileKey] и [emailKey].
     * Отсутствующая запись и `FAILED` не блокируют попытку; `ATTEMPTING`, `UNKNOWN`
     * и `DELIVERED` блокируют. Перед чтением зависшие попытки переводятся в `UNKNOWN`.
     */
    @Synchronized
    fun isBlocked(profileKey: String, emailKey: String): Boolean {
        recoverStaleAttempts()
        return connection.prepareStatement(
            "SELECT status FROM delivery_history WHERE profile_key = ? AND email_key = ?"
        ).use { statement ->
            statement.setString(1, profileKey)
            statement.setString(2, emailKey)
            statement.executeQuery().use { result ->
                result.next() && result.getString("status") != DeliveryStatus.FAILED.name
            }
        }
    }

    /**
     * Атомарно резервирует письмо перед внешним запросом к Telegram.
     *
     * Новая запись получает статус `ATTEMPTING`; существующая может быть обновлена
     * только после гарантированного отказа [DeliveryStatus.FAILED]. Возвращает `true`,
     * если резервация создана, и `false`, если дедупликационный ключ уже заблокирован.
     * Текстовые метаданные письма ограничиваются перед записью, а тело в журнал
     * не сохраняется.
     */
    @Synchronized
    fun beginAttempt(
        profileKey: String,
        emailKey: String,
        message: MailMessage,
        uidValidity: Long,
    ): Boolean {
        val now = System.currentTimeMillis()
        // Повторная вставка разрешена только после гарантированного отказа.
        return connection.prepareStatement(
            """
            INSERT INTO delivery_history (
                profile_key, email_key, sender, subject, received_at_ms, uid_validity, imap_uid,
                status, reserved_at_ms, updated_at_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'ATTEMPTING', ?, ?)
            ON CONFLICT(profile_key, email_key) DO UPDATE SET
                status = 'ATTEMPTING', reserved_at_ms = excluded.reserved_at_ms,
                updated_at_ms = excluded.updated_at_ms, last_error = NULL
            WHERE delivery_history.status = 'FAILED'
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, profileKey)
            statement.setString(2, emailKey)
            statement.setString(3, message.from.take(500))
            statement.setString(4, message.subject.take(500))
            message.receivedAt?.toEpochMilli()?.let { statement.setLong(5, it) } ?: statement.setNull(5, java.sql.Types.BIGINT)
            statement.setLong(6, uidValidity)
            statement.setLong(7, message.uid)
            statement.setLong(8, now)
            statement.setLong(9, now)
            statement.executeUpdate() == 1
        }
    }

    /**
     * Фиксирует подтверждённую Telegram-доставку и необязательный ID сообщения.
     * Допустим переход из `ATTEMPTING` или `UNKNOWN`; отсутствие подходящей
     * резервации считается нарушением контракта и приводит к исключению.
     */
    @Synchronized
    fun markDelivered(profileKey: String, emailKey: String, telegramMessageId: Long?) {
        updateStatus(profileKey, emailKey, DeliveryStatus.DELIVERED, telegramMessageId, null)
    }

    /**
     * Фиксирует неоднозначный результат сетевой попытки, который нельзя безопасно
     * повторять автоматически. Переход допустим только из `ATTEMPTING`.
     */
    @Synchronized
    fun markUnknown(profileKey: String, emailKey: String, error: String?) {
        updateStatus(profileKey, emailKey, DeliveryStatus.UNKNOWN, null, error)
    }

    /**
     * Фиксирует гарантированный отказ из состояния `ATTEMPTING`.
     * После этого та же пара ключей может быть повторно зарезервирована.
     */
    @Synchronized
    fun markFailed(profileKey: String, emailKey: String, error: String?) {
        updateStatus(profileKey, emailKey, DeliveryStatus.FAILED, null, error)
    }

    /**
     * Возвращает не более [limit] последних записей [profileKey] в порядке убывания
     * времени изменения. Перед выборкой зависшие попытки переводятся в `UNKNOWN`.
     */
    @Synchronized
    fun recent(profileKey: String, limit: Int = 10): List<DeliveryRecord> {
        recoverStaleAttempts()
        return connection.prepareStatement(
            """
            SELECT email_key, sender, subject, status, received_at_ms, updated_at_ms
            FROM delivery_history WHERE profile_key = ? ORDER BY updated_at_ms DESC LIMIT ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, profileKey)
            statement.setInt(2, limit)
            statement.executeQuery().use { result -> buildList { while (result.next()) add(result.toDeliveryRecord()) } }
        }
    }

    /**
     * Возвращает устойчивый статус действия GitLab либо `null`, если письмо ещё не
     * обрабатывалось в этом профиле. Перед чтением просроченная резервация переводится
     * из `ATTEMPTING` в повторяемый `FAILED`.
     */
    @Synchronized
    fun gitLabActionStatus(profileKey: String, emailKey: String): GitLabActionStatus? {
        recoverStaleGitLabActions()
        return connection.prepareStatement(
            "SELECT status FROM gitlab_action_history WHERE profile_key = ? AND email_key = ?"
        ).use { statement ->
            statement.setString(1, profileKey)
            statement.setString(2, emailKey)
            statement.executeQuery().use { result ->
                if (result.next()) GitLabActionStatus.valueOf(result.getString("status")) else null
            }
        }
    }

    /**
     * Атомарно резервирует действие GitLab. Новая запись и прежний `FAILED` переходят
     * в `ATTEMPTING`; `SUCCEEDED` и уже выполняющийся `ATTEMPTING` не перезаписываются.
     * Возвращает `true`, только если вызывающий получил право выполнить внешний запрос.
     */
    @Synchronized
    fun beginGitLabAction(profileKey: String, emailKey: String): Boolean {
        val now = System.currentTimeMillis()
        return connection.prepareStatement(
            """
            INSERT INTO gitlab_action_history (profile_key, email_key, status, reserved_at_ms, updated_at_ms)
            VALUES (?, ?, 'ATTEMPTING', ?, ?)
            ON CONFLICT(profile_key, email_key) DO UPDATE SET
                status = 'ATTEMPTING', reserved_at_ms = excluded.reserved_at_ms,
                updated_at_ms = excluded.updated_at_ms, last_error = NULL
            WHERE gitlab_action_history.status = 'FAILED'
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, profileKey)
            statement.setString(2, emailKey)
            statement.setLong(3, now)
            statement.setLong(4, now)
            statement.executeUpdate() == 1
        }
    }

    /** Завершает текущую резервацию GitLab как успешно выполненную и блокирует повторы. */
    @Synchronized
    fun markGitLabActionSucceeded(profileKey: String, emailKey: String) {
        updateGitLabStatus(profileKey, emailKey, GitLabActionStatus.SUCCEEDED, null)
    }

    /**
     * Завершает текущую резервацию GitLab как неуспешную, сохраняя ограниченный текст
     * ошибки. Такой статус разрешает следующему циклу зарезервировать повтор.
     */
    @Synchronized
    fun markGitLabActionFailed(profileKey: String, emailKey: String, error: String?) {
        updateGitLabStatus(profileKey, emailKey, GitLabActionStatus.FAILED, error)
    }

    private fun recoverStaleAttempts() {
        // После сбоя неизвестно, принял ли Telegram запрос, поэтому зависшая попытка не становится FAILED.
        connection.prepareStatement(
            "UPDATE delivery_history SET status = 'UNKNOWN', updated_at_ms = ? " +
                "WHERE status = 'ATTEMPTING' AND reserved_at_ms < ?"
        ).use { statement ->
            val now = System.currentTimeMillis()
            statement.setLong(1, now)
            statement.setLong(2, now - STALE_ATTEMPT_MS)
            statement.executeUpdate()
        }
    }

    private fun recoverStaleGitLabActions() {
        // Изменение меток GitLab повторяемо, поэтому прерванную резервацию можно освободить для retry.
        connection.prepareStatement(
            "UPDATE gitlab_action_history SET status = 'FAILED', updated_at_ms = ?, " +
                "last_error = 'Прерванная попытка' WHERE status = 'ATTEMPTING' AND reserved_at_ms < ?"
        ).use { statement ->
            val now = System.currentTimeMillis()
            statement.setLong(1, now)
            statement.setLong(2, now - STALE_GITLAB_ACTION_MS)
            statement.executeUpdate()
        }
    }

    private fun updateGitLabStatus(
        profileKey: String,
        emailKey: String,
        status: GitLabActionStatus,
        error: String?,
    ) {
        require(status != GitLabActionStatus.ATTEMPTING)
        val now = System.currentTimeMillis()
        connection.prepareStatement(
            """
            UPDATE gitlab_action_history SET status = ?, succeeded_at_ms = ?, updated_at_ms = ?, last_error = ?
            WHERE profile_key = ? AND email_key = ? AND status = 'ATTEMPTING'
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, status.name)
            if (status == GitLabActionStatus.SUCCEEDED) statement.setLong(2, now)
            else statement.setNull(2, java.sql.Types.BIGINT)
            statement.setLong(3, now)
            error?.let { statement.setString(4, it.take(500)) } ?: statement.setNull(4, java.sql.Types.VARCHAR)
            statement.setString(5, profileKey)
            statement.setString(6, emailKey)
            check(statement.executeUpdate() == 1) { "GitLab action reservation not found" }
        }
    }

    private fun updateStatus(
        profileKey: String,
        emailKey: String,
        status: DeliveryStatus,
        telegramMessageId: Long?,
        error: String?,
    ) {
        val now = System.currentTimeMillis()
        // SQL дополнительно защищает допустимый граф переходов от ошибочного вызова API хранилища.
        val allowedSource = when (status) {
            DeliveryStatus.DELIVERED -> "('ATTEMPTING', 'UNKNOWN')"
            DeliveryStatus.UNKNOWN, DeliveryStatus.FAILED -> "('ATTEMPTING')"
            DeliveryStatus.ATTEMPTING -> error("Use beginAttempt for ATTEMPTING")
        }
        connection.prepareStatement(
            """
            UPDATE delivery_history SET status = ?, telegram_message_id = ?, delivered_at_ms = ?,
                updated_at_ms = ?, last_error = ? WHERE profile_key = ? AND email_key = ?
                AND status IN $allowedSource
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, status.name)
            telegramMessageId?.let { statement.setLong(2, it) } ?: statement.setNull(2, java.sql.Types.BIGINT)
            if (status == DeliveryStatus.DELIVERED) statement.setLong(3, now) else statement.setNull(3, java.sql.Types.BIGINT)
            statement.setLong(4, now)
            error?.let { statement.setString(5, it.take(500)) } ?: statement.setNull(5, java.sql.Types.VARCHAR)
            statement.setString(6, profileKey)
            statement.setString(7, emailKey)
            check(statement.executeUpdate() == 1) { "Delivery reservation not found" }
        }
    }

    private fun ResultSet.toDeliveryRecord() = DeliveryRecord(
        emailKey = getString("email_key"),
        sender = getString("sender"),
        subject = getString("subject"),
        status = DeliveryStatus.valueOf(getString("status")),
        receivedAt = getLong("received_at_ms").let { value -> if (wasNull()) null else Instant.ofEpochMilli(value) },
        updatedAt = Instant.ofEpochMilli(getLong("updated_at_ms")),
    )

    /** Закрывает принадлежащее журналу JDBC-подключение к SQLite. */
    override fun close() = connection.close()

    companion object {
        private const val STALE_ATTEMPT_MS = 2 * 60_000L
        private const val STALE_GITLAB_ACTION_MS = 2 * 60_000L
        private const val SCHEMA_VERSION = 2

        /**
         * Возвращает платформенный путь к базе истории в пользовательском каталоге
         * данных: Application Support на macOS, APPDATA на Windows и XDG-совместимый
         * каталог на остальных системах. Родительский каталог создаётся конструктором.
         */

        fun defaultDatabasePath(): Path {
            val home = System.getProperty("user.home")
            return when (OsType.current) {
                OsType.Mac -> Paths.get(home, "Library", "Application Support", "AgentMail", "history.db")
                OsType.Windows -> Paths.get(System.getenv("APPDATA") ?: home, "AgentMail", "history.db")
                OsType.Linux -> Paths.get(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "agentmail", "history.db")
            }
        }
    }
}
