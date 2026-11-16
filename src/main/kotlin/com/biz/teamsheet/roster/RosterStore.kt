package com.biz.teamsheet.roster

import com.biz.teamsheet.model.Player
import java.sql.DriverManager
import org.flywaydb.core.Flyway
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper

data class RosterDraft(
    val players: List<Player> = emptyList(),
    val attendingIds: Set<String> = emptySet(),
    val guests: List<Player> = emptyList(),
)

data class SavedRoster(val revision: Int, val draft: RosterDraft)

@Repository
@Profile("roster")
class RosterStore(
    @Value("\${teamsheet.roster.jdbc-url}") private val url: String,
    @Value("\${teamsheet.roster.username}") private val username: String,
    @Value("\${teamsheet.roster.password}") private val password: String,
    private val json: JsonMapper,
) {
    init {
        Flyway.configure().dataSource(url, username, password).load().migrate()
    }

    fun read(subject: String): SavedRoster = DriverManager.getConnection(url, username, password).use { connection ->
        connection.prepareStatement("SELECT revision, payload FROM saved_rosters WHERE account_subject = ?").use { statement ->
            statement.setString(1, subject)
            statement.executeQuery().use { rows ->
                if (rows.next()) SavedRoster(rows.getInt(1), json.readValue(rows.getString(2), RosterDraft::class.java))
                else SavedRoster(0, RosterDraft())
            }
        }
    }

    fun save(subject: String, roster: SavedRoster): Boolean {
        val sql = if (roster.revision == 0) """
            INSERT INTO saved_rosters (payload, account_subject, revision) VALUES (CAST(? AS jsonb), ?, 1)
            ON CONFLICT (account_subject) DO NOTHING
        """ else """
            UPDATE saved_rosters SET payload = CAST(? AS jsonb), revision = revision + 1
            WHERE account_subject = ? AND revision = ?
        """
        return DriverManager.getConnection(url, username, password).use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, json.writeValueAsString(roster.draft))
                statement.setString(2, subject)
                if (roster.revision > 0) statement.setInt(3, roster.revision)
                statement.executeUpdate() == 1
            }
        }
    }
}
