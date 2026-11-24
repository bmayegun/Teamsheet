package com.biz.teamsheet.roster

import org.springframework.transaction.annotation.Transactional
import com.biz.teamsheet.club.ClubStore
import java.util.UUID
import org.springframework.web.bind.annotation.RequestParam
import jakarta.servlet.http.HttpServletRequest
import com.biz.teamsheet.auth.AccountAuthentication
import com.biz.teamsheet.auth.RosterAccount
import com.biz.teamsheet.auth.EmailSignIn
import com.biz.teamsheet.auth.rosterAccount
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

data class RosterSession(
    val enabled: Boolean,
    val name: String?,
    val csrfToken: String?,
    val emailSignInEnabled: Boolean = false,
    val canLinkEmail: Boolean = false,
    val linkedEmail: String? = null,
    val signInMethod: String? = null,
    val hasPassword: Boolean = false,
    val canSetPassword: Boolean = false,
    val passwordVerificationExpiresAt: Long? = null,
)

@RestController
@Profile("!roster")
class PublicSessionController {
    @GetMapping("/api/session")
    fun session() = RosterSession(false, null, null)
}

@RestController
@Profile("roster")
class RosterController(private val store: RosterStore, private val emailSignIn: EmailSignIn, private val clubs: ClubStore) {
    @GetMapping("/api/session")
    @Transactional(readOnly = true)
    fun session(@AuthenticationPrincipal principal: Any?, csrf: CsrfToken, request: HttpServletRequest): ResponseEntity<RosterSession> {
        val account = rosterAccount(principal)
        val emailEnabled = emailSignIn.enabled
        val csrfToken = csrf.token
        val identity = account?.let { emailSignIn.identity(it.subject) }
        val linkedEmail = identity?.email
        return privateResponse(RosterSession(true, account?.let { clubs.preferredName(it.subject) ?: it.displayName }, csrfToken, emailEnabled,
            emailEnabled && principal is OidcUser && account != null && linkedEmail == null, linkedEmail,
            when { principal is OidcUser -> "google"; principal is RosterAccount -> principal.method; else -> null },
            identity?.hasPassword == true,
            account != null && AccountAuthentication.recentlyVerified(account.subject, request),
            account?.let { AccountAuthentication.verificationExpiresAt(it.subject, request) }))
    }

    data class DisplayName(val name: String)

    @PutMapping("/api/account/display-name")
    fun renameAccount(@AuthenticationPrincipal principal: Any?, @RequestBody body: DisplayName): ResponseEntity<DisplayName> {
        val account = rosterAccount(principal) ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        return privateResponse(DisplayName(clubs.savePreferredName(account.subject, body.name)))
    }

    @GetMapping("/api/roster")
    fun roster(@AuthenticationPrincipal principal: Any?, @RequestParam(required = false) clubId: UUID? = null): ResponseEntity<SavedRoster> =
        withRosterAccess(principal, clubId) { subject -> privateResponse(store.read(subject)) }

    @PutMapping("/api/roster")
    fun save(@AuthenticationPrincipal principal: Any?, @RequestBody roster: SavedRoster, @RequestParam(required = false) clubId: UUID? = null): ResponseEntity<SavedRoster> {
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
        return withRosterAccess(principal, clubId) { subject ->
            if (!store.save(subject, roster)) throw ResponseStatusException(HttpStatus.CONFLICT, "Roster changed on another device.")
            privateResponse(roster.copy(revision = roster.revision + 1))
        }
    }

    private fun <T> withRosterAccess(principal: Any?, clubId: UUID?, action: (String) -> T): T {
        val account = rosterAccount(principal) ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        return if (clubId == null) action(account.subject) else clubs.withRosterAccess(account.subject, clubId, action)
    }

    private fun <T : Any> privateResponse(body: T): ResponseEntity<T> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
}
