package com.biz.teamsheet.roster

import com.biz.teamsheet.model.Player
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
@org.springframework.transaction.annotation.Transactional(rollbackFor = [Exception::class])
class RosterStore(
    private val jdbc: org.springframework.jdbc.core.simple.JdbcClient,
    private val json: JsonMapper,
) {
    fun read(subject: String): SavedRoster = jdbc.sql("SELECT revision, payload FROM saved_rosters WHERE account_subject = ?")
        .param(subject)
        .query { rows, _ -> SavedRoster(rows.getInt(1), json.readValue(rows.getString(2), RosterDraft::class.java)) }
        .optional().orElseGet { SavedRoster(0, RosterDraft()) }

    fun save(subject: String, roster: SavedRoster): Boolean {
        val sql = if (roster.revision == 0) """
            INSERT INTO saved_rosters (payload, account_subject, revision) VALUES (CAST(? AS jsonb), ?, 1)
            ON CONFLICT (account_subject) DO NOTHING
        """ else """
            UPDATE saved_rosters SET payload = CAST(? AS jsonb), revision = revision + 1
            WHERE account_subject = ? AND revision = ?
        """
        val query = jdbc.sql(sql).param(json.writeValueAsString(roster.draft)).param(subject)
        return (if (roster.revision > 0) query.param(roster.revision) else query).update() == 1
    }
}
