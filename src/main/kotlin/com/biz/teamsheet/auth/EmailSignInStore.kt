package com.biz.teamsheet.auth

import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Repository

internal sealed interface EmailVerification {
    data class Verified(val subject: String) : EmailVerification
    data object Invalid : EmailVerification
    data object AlreadyLinked : EmailVerification
}

@Repository
@Profile("roster")
class EmailSignInStore(
    @Value("\${teamsheet.roster.jdbc-url}") private val url: String,
    @Value("\${teamsheet.roster.username}") private val username: String,
    @Value("\${teamsheet.roster.password}") private val password: String,
) {
    internal data class EmailIdentity(val email: String, val hasPassword: Boolean)

    internal fun identity(subject: String): EmailIdentity? = transaction { connection ->
        connection.prepareStatement("SELECT email, password_hash IS NOT NULL FROM email_sign_in_identities WHERE account_subject = ?").use {
            it.setString(1, subject)
            it.executeQuery().use { rows -> if (rows.next()) EmailIdentity(rows.getString(1), rows.getBoolean(2)) else null }
        }
    }

    internal fun linkedEmail(subject: String): String? = transaction { connection ->
        connection.prepareStatement("SELECT email FROM email_sign_in_identities WHERE account_subject = ?").use {
            it.setString(1, subject)
            it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

    internal fun issue(email: String, id: UUID, browser: String, account: String?, hash: ByteArray): Unit =
        transaction { connection ->
            // Reserve the shared budget first to serialize concurrent send reservations.
            val reserved = connection.prepareStatement("""
                INSERT INTO email_sign_in_send_limits (utc_day, sent_count)
                VALUES ((now() AT TIME ZONE 'UTC')::date, 1)
                ON CONFLICT (utc_day) DO UPDATE SET sent_count = email_sign_in_send_limits.sent_count + 1
                WHERE email_sign_in_send_limits.sent_count < 200
            """).use { it.executeUpdate() == 1 }

            if (!reserved) throw EmailSendLimit(daily = true)
            val issued = connection.prepareStatement("""
                INSERT INTO email_sign_in_codes (email, challenge_id, browser_id, link_subject, code_hash, attempts_left)
                VALUES (?, ?, ?, ?, ?, 5)
                ON CONFLICT (email) DO UPDATE SET challenge_id = excluded.challenge_id, browser_id = excluded.browser_id,
                    link_subject = excluded.link_subject, code_hash = excluded.code_hash, issued_at = now(), attempts_left = 5,
                    send_day = excluded.send_day, daily_sends = CASE WHEN email_sign_in_codes.send_day = excluded.send_day
                        THEN email_sign_in_codes.daily_sends + 1 ELSE 1 END
                WHERE email_sign_in_codes.issued_at <= now() - interval '1 minute'
                    AND (email_sign_in_codes.send_day <> excluded.send_day OR email_sign_in_codes.daily_sends < 8)
            """).use {
                it.setString(1, email)
                it.setObject(2, id)
                it.setString(3, browser)
                it.setString(4, account)
                it.setBytes(5, hash)
                it.executeUpdate() == 1
            }
            if (!issued) {
                val limit = connection.prepareStatement("""
                    SELECT daily_sends >= 8 AND send_day = (now() AT TIME ZONE 'UTC')::date,
                        greatest(1, ceil(extract(epoch from issued_at + interval '1 minute' - now())))::int
                    FROM email_sign_in_codes WHERE email = ?
                """).use {
                    it.setString(1, email)
                    it.executeQuery().use { row -> check(row.next()); EmailSendLimit(row.getBoolean(1), row.getInt(2)) }
                }
                throw limit
            }
            connection.createStatement().use {
                it.executeUpdate("DELETE FROM email_sign_in_codes WHERE issued_at < now() - interval '1 day'")
                it.executeUpdate("DELETE FROM email_sign_in_send_limits WHERE utc_day < (now() AT TIME ZONE 'UTC')::date - 1")
            }
            Unit
        }

    internal fun verify(id: UUID, browser: String, account: String?, hash: ByteArray): EmailVerification =
        transaction { connection ->
            val challenge = connection.prepareStatement("""
                SELECT email, code_hash FROM email_sign_in_codes
                WHERE challenge_id = ? AND browser_id = ? AND link_subject IS NOT DISTINCT FROM ?
                    AND attempts_left > 0 AND issued_at > now() - interval '10 minutes'
                FOR UPDATE
            """).use {
                it.setObject(1, id)
                it.setString(2, browser)
                it.setString(3, account)
                it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) to rows.getBytes(2) else null }
            } ?: return@transaction EmailVerification.Invalid

            val (email, expectedHash) = challenge
            val matches = MessageDigest.isEqual(expectedHash, hash)
            connection.prepareStatement("""
                UPDATE email_sign_in_codes SET attempts_left = CASE WHEN ? THEN 0 ELSE attempts_left - 1 END
                WHERE challenge_id = ?
            """).use {
                it.setBoolean(1, matches)
                it.setObject(2, id)
                it.executeUpdate()
            }
            if (!matches) return@transaction EmailVerification.Invalid

            val proposedSubject = account ?: "email:${UUID.randomUUID()}"
            connection.prepareStatement("""
                INSERT INTO email_sign_in_identities (email, account_subject) VALUES (?, ?)
                ON CONFLICT DO NOTHING
            """).use {
                it.setString(1, email)
                it.setString(2, proposedSubject)
                it.executeUpdate()
            }
            val subject = connection.prepareStatement("""
                SELECT account_subject FROM email_sign_in_identities WHERE email = ?
            """).use {
                it.setString(1, email)
                it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
            if (subject == null || (account != null && account != subject)) EmailVerification.AlreadyLinked
            else EmailVerification.Verified(subject)
        }

    internal data class Credentials(val subject: String, val hash: String?)

    internal fun credentials(email: String): Credentials? = transaction { connection ->
        connection.prepareStatement("SELECT account_subject, password_hash FROM email_sign_in_identities WHERE email = ?").use {
            it.setString(1, email)
            it.executeQuery().use { rows -> if (rows.next()) Credentials(rows.getString(1), rows.getString(2)) else null }
        }
    }

    internal fun savePassword(subject: String, previousHash: String?, hash: String): Boolean = transaction { connection ->
        connection.prepareStatement("""
            UPDATE email_sign_in_identities SET password_hash = ?
            WHERE account_subject = ? AND password_hash IS NOT DISTINCT FROM ?
        """).use {
            it.setString(1, hash)
            it.setString(2, subject)
            it.setString(3, previousHash)
            it.executeUpdate() == 1
        }
    }

    internal fun reservePasswordAttempt(email: String): Boolean = transaction { connection ->
        val key = MessageDigest.getInstance("SHA-256").digest(email.toByteArray()).joinToString("") { "%02x".format(it) }
        for ((bucket, limit) in listOf("global" to 500, key to 10)) {
            val reserved = connection.prepareStatement("""
                INSERT INTO password_sign_in_limits (bucket) VALUES (?)
                ON CONFLICT (bucket) DO UPDATE SET
                    attempts = CASE WHEN password_sign_in_limits.started_at <= now() - interval '15 minutes'
                        THEN 1 ELSE password_sign_in_limits.attempts + 1 END,
                    started_at = CASE WHEN password_sign_in_limits.started_at <= now() - interval '15 minutes'
                        THEN now() ELSE password_sign_in_limits.started_at END
                WHERE password_sign_in_limits.attempts < ?
                    OR password_sign_in_limits.started_at <= now() - interval '15 minutes'
            """).use {
                it.setString(1, bucket)
                it.setInt(2, limit)
                it.executeUpdate() == 1
            }
            if (!reserved) return@transaction false
        }
        connection.createStatement().use {
            it.executeUpdate("DELETE FROM password_sign_in_limits WHERE started_at < now() - interval '1 day'")
        }
        true
    }

    private fun <T> transaction(action: (Connection) -> T): T =
        DriverManager.getConnection(url, username, password).use { connection ->
            connection.autoCommit = false
            try {
                action(connection).also { connection.commit() }
            } catch (error: Exception) {
                connection.rollback()
                throw error
            }
        }
}
