package com.biz.teamsheet.club

import com.biz.teamsheet.auth.rosterAccount
import java.util.UUID
import org.springframework.context.annotation.Profile
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@Profile("roster")
@RequestMapping("/api/clubs")
class ClubController(private val store: ClubStore) {
    data class CreateClub(val name: String)
    data class AcceptInvitation(val token: String)
    data class Invitation(val token: String)
    data class WorkspaceName(val name: String)

    @GetMapping("/personal-workspace")
    fun personalName(@AuthenticationPrincipal principal: Any?) =
        privateResponse(WorkspaceName(store.personalName(subject(principal))))

    @PutMapping("/personal-workspace")
    fun renamePersonal(@AuthenticationPrincipal principal: Any?, @RequestBody body: WorkspaceName) =
        privateResponse(WorkspaceName(store.renamePersonal(subject(principal), body.name)))

    @GetMapping
    fun list(@AuthenticationPrincipal principal: Any?) = privateResponse(store.list(subject(principal)))

    @PostMapping
    fun create(@AuthenticationPrincipal principal: Any?, @RequestBody body: CreateClub): ResponseEntity<Club> {
        val account = rosterAccount(principal) ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val club = store.create(account.subject, body.name)
        store.updateDisplayName(account.subject, account.displayName)
        return privateResponse(club)
    }

    @PostMapping("/{clubId}/invitations")
    fun invite(@AuthenticationPrincipal principal: Any?, @PathVariable clubId: UUID) =
        privateResponse(Invitation(store.invite(subject(principal), clubId)))

    @PutMapping("/{clubId}")
    fun rename(@AuthenticationPrincipal principal: Any?, @PathVariable clubId: UUID, @RequestBody body: WorkspaceName) =
        privateResponse(store.rename(subject(principal), clubId, body.name))

    @DeleteMapping("/{clubId}")
    fun delete(@AuthenticationPrincipal principal: Any?, @PathVariable clubId: UUID): ResponseEntity<Void> {
        store.delete(subject(principal), clubId)
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }

    @GetMapping("/{clubId}/members")
    fun members(@AuthenticationPrincipal principal: Any?, @PathVariable clubId: UUID) =
        privateResponse(store.management(subject(principal), clubId))

    @DeleteMapping("/{clubId}/members/{memberId}")
    fun remove(@AuthenticationPrincipal principal: Any?, @PathVariable clubId: UUID, @PathVariable memberId: UUID): ResponseEntity<Void> {
        store.removeMember(subject(principal), clubId, memberId)
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }

    @DeleteMapping("/{clubId}/invitations")
    fun revoke(@AuthenticationPrincipal principal: Any?, @PathVariable clubId: UUID): ResponseEntity<Void> {
        store.revokeInvitations(subject(principal), clubId)
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }

    @PostMapping("/invitations/preview")
    fun preview(@AuthenticationPrincipal principal: Any?, @RequestBody body: AcceptInvitation): ResponseEntity<InvitationPreview> {
        subject(principal)
        return privateResponse(store.preview(body.token))
    }

    @PostMapping("/invitations/accept")
    fun accept(@AuthenticationPrincipal principal: Any?, @RequestBody body: AcceptInvitation): ResponseEntity<Club> {
        val account = rosterAccount(principal) ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val club = store.accept(account.subject, body.token)
        store.updateDisplayName(account.subject, account.displayName)
        return privateResponse(club)
    }

    private fun subject(principal: Any?) =
        rosterAccount(principal)?.subject ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

    private fun <T : Any> privateResponse(body: T): ResponseEntity<T> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
}
