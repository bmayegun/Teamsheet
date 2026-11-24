package com.biz.teamsheet.club

import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.time.Instant
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.DependsOn
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Repository
import org.springframework.web.server.ResponseStatusException

data class Club(val id: UUID, val name: String, val role: String)
data class ClubMember(val id: UUID, val name: String, val role: String, val isYou: Boolean)
data class ClubManagement(val members: List<ClubMember>, val invitationExpiresAt: Instant?)
data class InvitationPreview(val clubId: UUID, val name: String, val role: String = "ORGANISER")

@Repository
@Profile("roster")
@DependsOn("rosterStore")
class ClubStore(
    @Value("\${teamsheet.roster.jdbc-url}") private val url: String,
    @Value("\${teamsheet.roster.username}") private val username: String,
    @Value("\${teamsheet.roster.password}") private val password: String,
) {
    fun personalName(subject: String): String = transaction { connection ->
        connection.prepareStatement("SELECT name FROM personal_workspace_names WHERE account_subject = ?").use {
            it.setString(1, subject)
            it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else "My players" }
        }
    }

    fun renamePersonal(subject: String, name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 100) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a workspace name between 1 and 100 characters.")
        }
        return transaction { connection ->
            connection.prepareStatement("""
                INSERT INTO personal_workspace_names (account_subject, name) VALUES (?, ?)
                ON CONFLICT (account_subject) DO UPDATE SET name = excluded.name
            """).use {
                it.setString(1, subject)
                it.setString(2, trimmed)
                it.executeUpdate()
            }
            trimmed
        }
    }

    fun list(subject: String): List<Club> = transaction { connection ->
        connection.prepareStatement("""
            SELECT c.id, c.name, m.role FROM clubs c JOIN club_members m ON c.id = m.club_id
            WHERE m.account_subject = ? ORDER BY c.name, c.id
        """).use {
            it.setString(1, subject)
            it.executeQuery().use { rows -> buildList {
                while (rows.next()) add(Club(rows.getObject(1, UUID::class.java), rows.getString(2), rows.getString(3)))
            } }
        }
    }

    fun create(subject: String, name: String): Club {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 100) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a club name between 1 and 100 characters.")
        }
        return transaction { connection ->
            val id = UUID.randomUUID()
            connection.prepareStatement("INSERT INTO clubs (id, name) VALUES (?, ?)").use {
                it.setObject(1, id)
                it.setString(2, trimmed)
                it.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO club_members (club_id, account_subject, role) VALUES (?, ?, 'OWNER')").use {
                it.setObject(1, id)
                it.setString(2, subject)
                it.executeUpdate()
            }
            Club(id, trimmed, "OWNER")
        }
    }

    fun rosterSubject(subject: String, clubId: UUID): String = transaction { connection ->
        requireMember(connection, subject, clubId)
        "club:$clubId"
    }

    fun preferredName(subject: String): String? = transaction { connection ->
        connection.prepareStatement("SELECT name FROM account_display_names WHERE account_subject = ?").use {
            it.setString(1, subject)
            it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

    fun savePreferredName(subject: String, input: String): String {
        val name = input.trim()
        if (name.isEmpty() || name.length > 100) throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        return transaction { connection ->
            connection.prepareStatement("""
                INSERT INTO account_display_names (account_subject, name) VALUES (?, ?)
                ON CONFLICT (account_subject) DO UPDATE SET name = excluded.name
            """).use {
                it.setString(1, subject)
                it.setString(2, name)
                it.executeUpdate()
            }
            name
        }
    }

    fun updateDisplayName(subject: String, name: String) {
        if (name == "Organiser" || name.isBlank()) return
        transaction { connection ->
            connection.prepareStatement("UPDATE club_members SET display_name = ? WHERE account_subject = ? AND display_name <> ?").use {
                it.setString(1, name.take(200))
                it.setString(2, subject)
                it.setString(3, name.take(200))
                it.executeUpdate()
            }
        }
    }

    fun rename(subject: String, clubId: UUID, name: String): Club {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 100) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a club name between 1 and 100 characters.")
        }
        return transaction { connection ->
            lockClub(connection, clubId)
            requireOwner(connection, subject, clubId)
            connection.prepareStatement("UPDATE clubs SET name = ? WHERE id = ?").use {
                it.setString(1, trimmed)
                it.setObject(2, clubId)
                it.executeUpdate()
            }
            Club(clubId, trimmed, "OWNER")
        }
    }

    fun management(subject: String, clubId: UUID): ClubManagement = transaction { connection ->
        requireMember(connection, subject, clubId)
        val members = connection.prepareStatement("""
            SELECT m.id, COALESCE(n.name, NULLIF(NULLIF(trim(m.display_name), ''), 'Organiser'),
                'Member ' || left(m.id::text, 8)) AS member_name,
                m.role, m.account_subject = ?
            FROM club_members m LEFT JOIN account_display_names n ON n.account_subject = m.account_subject
            WHERE m.club_id = ? ORDER BY m.role DESC, member_name, m.id
        """).use {
            it.setString(1, subject)
            it.setObject(2, clubId)
            it.executeQuery().use { rows -> buildList {
                while (rows.next()) add(ClubMember(rows.getObject(1, UUID::class.java), rows.getString(2), rows.getString(3), rows.getBoolean(4)))
            } }
        }
        val expires = connection.prepareStatement("""
            SELECT max(expires_at) FROM club_invitations WHERE club_id = ? AND accepted_by IS NULL AND expires_at > now()
        """).use {
            it.setObject(1, clubId)
            it.executeQuery().use { rows -> rows.next(); rows.getTimestamp(1)?.toInstant() }
        }
        ClubManagement(members, expires)
    }

    fun removeMember(subject: String, clubId: UUID, memberId: UUID) = transaction { connection ->
        lockClub(connection, clubId)
        requireOwner(connection, subject, clubId)
        val removedSubject = connection.prepareStatement("""
            DELETE FROM club_members WHERE club_id = ? AND id = ? AND role = 'ORGANISER' RETURNING account_subject
        """).use {
            it.setObject(1, clubId)
            it.setObject(2, memberId)
            it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        } ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "This organiser is unavailable. The owner cannot be removed.")
        connection.prepareStatement("DELETE FROM club_invitations WHERE club_id = ? AND accepted_by = ?").use {
            it.setObject(1, clubId)
            it.setString(2, removedSubject)
            it.executeUpdate()
        }
    }

    fun revokeInvitations(subject: String, clubId: UUID) = transaction { connection ->
        lockClub(connection, clubId)
        requireOwner(connection, subject, clubId)
        connection.prepareStatement("DELETE FROM club_invitations WHERE club_id = ? AND accepted_by IS NULL").use {
            it.setObject(1, clubId)
            it.executeUpdate()
        }
    }

    fun invite(subject: String, clubId: UUID): String = transaction { connection ->
        lockClub(connection, clubId)
        requireOwner(connection, subject, clubId)
        connection.prepareStatement("DELETE FROM club_invitations WHERE club_id = ? AND accepted_by IS NULL").use {
            it.setObject(1, clubId)
            it.executeUpdate()
        }
        val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        connection.prepareStatement("INSERT INTO club_invitations (token_hash, club_id, created_by) VALUES (?, ?, ?)").use {
            it.setString(1, hash(token))
            it.setObject(2, clubId)
            it.setString(3, subject)
            it.executeUpdate()
        }
        token
    }

    fun preview(token: String): InvitationPreview = transaction { connection ->
        val clubId = invitationClub(connection, token)
        connection.prepareStatement("""
            SELECT c.name FROM club_invitations i JOIN clubs c ON c.id = i.club_id
            WHERE i.token_hash = ? AND i.expires_at > now() AND i.accepted_by IS NULL
        """).use {
            it.setString(1, hash(token))
            it.executeQuery().use { rows ->
                if (!rows.next()) throw invalidInvitation()
                InvitationPreview(clubId, rows.getString(1))
            }
        }
    }

    fun accept(subject: String, token: String): Club = transaction { connection ->
        val clubId = invitationClub(connection, token)
        lockClub(connection, clubId)
        val accepted = connection.prepareStatement("""
            SELECT accepted_by FROM club_invitations WHERE token_hash = ? AND expires_at > now() FOR UPDATE
        """).use {
            it.setString(1, hash(token))
            it.executeQuery().use { rows ->
                if (!rows.next()) throw invalidInvitation()
                rows.getString(1)
            }
        }
        if (accepted != null) {
            if (accepted != subject) throw invalidInvitation()
            requireMember(connection, subject, clubId)
        } else {
            connection.prepareStatement("""
                INSERT INTO club_members (club_id, account_subject, role) VALUES (?, ?, 'ORGANISER')
                ON CONFLICT DO NOTHING
            """).use {
                it.setObject(1, clubId)
                it.setString(2, subject)
                it.executeUpdate()
            }
            connection.prepareStatement("UPDATE club_invitations SET accepted_by = ? WHERE token_hash = ?").use {
                it.setString(1, subject)
                it.setString(2, hash(token))
                it.executeUpdate()
            }
        }
        connection.prepareStatement("SELECT name FROM clubs WHERE id = ?").use {
            it.setObject(1, clubId)
            it.executeQuery().use { rows ->
                check(rows.next())
                Club(clubId, rows.getString(1), requireMember(connection, subject, clubId))
            }
        }
    }

    fun <T> withRosterAccess(subject: String, clubId: UUID, action: (String) -> T): T = transaction { connection ->
        // Hold membership through the roster operation so removal waits for in-flight saves.
        connection.prepareStatement("SELECT role FROM club_members WHERE club_id = ? AND account_subject = ? FOR SHARE").use {
            it.setObject(1, clubId)
            it.setString(2, subject)
            it.executeQuery().use { rows ->
                if (!rows.next()) throw ResponseStatusException(HttpStatus.FORBIDDEN)
            }
        }
        action("club:$clubId")
    }

    private fun invitationClub(connection: Connection, token: String): UUID {
        if (token.length != 72) throw invalidInvitation()
        return connection.prepareStatement("SELECT club_id FROM club_invitations WHERE token_hash = ? AND expires_at > now()").use {
            it.setString(1, hash(token))
            it.executeQuery().use { rows -> if (rows.next()) rows.getObject(1, UUID::class.java) else throw invalidInvitation() }
        }
    }

    private fun lockClub(connection: Connection, clubId: UUID) {
        connection.prepareStatement("SELECT id FROM clubs WHERE id = ? FOR UPDATE").use {
            it.setObject(1, clubId)
            it.executeQuery().use { rows ->
                if (!rows.next()) throw ResponseStatusException(HttpStatus.NOT_FOUND)
            }
        }
    }

    private fun requireOwner(connection: Connection, subject: String, clubId: UUID) {
        if (requireMember(connection, subject, clubId) != "OWNER") {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only the club owner can manage access.")
        }
    }

    private fun requireMember(connection: Connection, subject: String, clubId: UUID): String =
        connection.prepareStatement("SELECT role FROM club_members WHERE club_id = ? AND account_subject = ?").use {
            it.setObject(1, clubId)
            it.setString(2, subject)
            it.executeQuery().use { rows ->
                if (rows.next()) rows.getString(1)
                else throw ResponseStatusException(HttpStatus.FORBIDDEN, "You are not a member of this club.")
            }
        }

    private fun hash(token: String) = MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun invalidInvitation() = ResponseStatusException(HttpStatus.BAD_REQUEST, "This invitation is invalid, expired, or already used.")

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
