package com.biz.teamsheet.auth

import org.springframework.web.bind.annotation.ExceptionHandler
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID
import org.springframework.context.annotation.Profile
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@Profile("roster")
class EmailSignInController(private val signIn: EmailSignIn, private val identities: EmailSignInStore) {
    data class CodeRequest(val email: String, val linking: Boolean = false)
    data class CodeVerification(val challengeId: UUID, val code: String, val linking: Boolean = false)
    data class Challenge(val challengeId: UUID)

    @ExceptionHandler(EmailSendLimit::class)
    internal fun limit(error: EmailSendLimit): ResponseEntity<Map<String, String>> {
        val response = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).cacheControl(CacheControl.noStore())
        if (!error.daily) response.header("Retry-After", error.waitSeconds.toString())
        return response.body(mapOf("limit" to if (error.daily) "daily" else "cooldown"))
    }

    @PostMapping("/api/auth/email/request")
    fun requestCode(@RequestBody body: CodeRequest, @AuthenticationPrincipal principal: Any?,
                    request: HttpServletRequest): ResponseEntity<Challenge> {
        val account = accountForFlow(principal, body.linking)
        val id = signIn.requestCode(body.email, request.getSession().id, account)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Challenge(id))
    }

    @PostMapping("/api/auth/email/verify")
    fun verify(@RequestBody body: CodeVerification, @AuthenticationPrincipal principal: Any?,
               request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Void> {
        val account = accountForFlow(principal, body.linking)
        val browser = request.getSession(false)?.id ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Request a new code.")
        val subject = signIn.verify(body.challengeId, body.code, browser, account)
        if (!body.linking) {
            AccountAuthentication.signIn(subject, "email", request, response, identities.credentialsVersion(subject))
        }
        AccountAuthentication.emailVerified(subject, request)
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }

    private fun accountForFlow(principal: Any?, linking: Boolean): String? {
        if (!signIn.enabled) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val account = rosterAccount(principal)
        if (linking && account == null) throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        if (!linking && account != null) throw ResponseStatusException(HttpStatus.CONFLICT)
        return account?.subject
    }
}
