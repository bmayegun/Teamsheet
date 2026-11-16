package com.biz.teamsheet.roster

import com.biz.teamsheet.service.TeamService
import org.springframework.context.annotation.Profile
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

data class RosterSession(val enabled: Boolean, val name: String?, val csrfToken: String?)

@RestController
@Profile("!roster")
class PublicSessionController {
    @GetMapping("/api/session")
    fun session() = RosterSession(false, null, null)
}

@RestController
@Profile("roster")
class RosterController(private val store: RosterStore) {
    @GetMapping("/api/session")
    fun session(@AuthenticationPrincipal user: OidcUser?, csrf: CsrfToken): ResponseEntity<RosterSession> =
        privateResponse(RosterSession(true, user?.let { it.fullName ?: "Organiser" }, user?.let { csrf.token }))

    @GetMapping("/api/roster")
    fun roster(@AuthenticationPrincipal user: OidcUser): ResponseEntity<SavedRoster> =
        privateResponse(store.read(accountSubject(user)))

    @PutMapping("/api/roster")
    fun save(@AuthenticationPrincipal user: OidcUser, @RequestBody roster: SavedRoster): ResponseEntity<SavedRoster> {
        val draft = roster.draft
        val players = draft.players + draft.guests
        val valid = roster.revision >= 0 && draft.players.size <= 1000 &&
            draft.attendingIds.size + draft.guests.size <= TeamService.MAX_PLAYERS &&
            players.map { it.id }.toSet().size == players.size &&
            players.all {
                it.id.isNotBlank() && it.id.length <= 100 && it.name.isNotBlank() && it.name.length <= 200 &&
                    it.skillRating in 1..5 && it.positions.distinct().size == it.positions.size
            } && draft.players.map { it.id }.toSet().containsAll(draft.attendingIds)
        if (!valid) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Check player details and match selection.")
        if (!store.save(accountSubject(user), roster)) throw ResponseStatusException(HttpStatus.CONFLICT, "Roster changed on another device.")
        return privateResponse(roster.copy(revision = roster.revision + 1))
    }

    private fun accountSubject(user: OidcUser): String =
        user.subject ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

    private fun <T : Any> privateResponse(body: T): ResponseEntity<T> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
}
