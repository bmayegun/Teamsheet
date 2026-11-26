package com.biz.teamsheet.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.Locale
import org.springframework.context.annotation.Profile
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.web.util.WebUtils
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@Service
@Profile("roster")
class PasswordAuthentication(private val store: EmailSignInStore, private val safety: PasswordSafety) {
    private val encoder = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()
    private val dummyHash = encoder.encode("unused-password-for-timing-only")

    internal fun authenticate(address: String, password: String): EmailSignInStore.Credentials {
        val email = address.trim().lowercase(Locale.ROOT)
        if (email.length > 254 || password.length > 128) throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        reserve(email)
        val credentials = store.credentials(email)
        val matches = encoder.matches(password, credentials?.hash ?: dummyHash)
        if (!matches || credentials?.hash == null) throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        return credentials
    }

    internal fun save(subject: String, password: String, currentPassword: String?, verified: Boolean, expectedVersion: Long?): Long {
        if (password.length !in 8..128) throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        val email = store.linkedEmail(subject) ?: throw ResponseStatusException(HttpStatus.CONFLICT)
        if (!verified) reserve(email)
        val credentials = store.credentials(email) ?: throw ResponseStatusException(HttpStatus.CONFLICT)
        if (expectedVersion != credentials.version) throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        if (!verified) {
            if (credentials.hash == null || currentPassword == null || currentPassword.length > 128 ||
                !encoder.matches(currentPassword, credentials.hash)) {
                throw ResponseStatusException(HttpStatus.FORBIDDEN)
            }
        }
        safety.check(password)
        if (!store.savePassword(subject, credentials.hash, checkNotNull(encoder.encode(password)), credentials.version)) {
            throw ResponseStatusException(HttpStatus.CONFLICT)
        }
        return credentials.version + 1
    }

    private fun reserve(email: String) {
        if (!store.reservePasswordAttempt(email)) throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS)
    }
}

@RestController
@Profile("roster")
class PasswordAuthenticationController(private val passwords: PasswordAuthentication) {
    data class Login(val email: String, val password: String)
    data class PasswordChange(val password: String, val currentPassword: String? = null)

    @PostMapping("/api/auth/password")
    fun signIn(@RequestBody body: Login, @AuthenticationPrincipal principal: Any?,
               request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Void> {
        if (rosterAccount(principal) != null) throw ResponseStatusException(HttpStatus.CONFLICT)
        val credentials = passwords.authenticate(body.email, body.password)
        AccountAuthentication.signIn(credentials.subject, "password", request, response, credentials.version)
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build<Void>()
    }

    @PutMapping("/api/account/password")
    fun save(@RequestBody body: PasswordChange, @AuthenticationPrincipal principal: Any?,
             request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<*> {
        val account = rosterAccount(principal) ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        synchronized(WebUtils.getSessionMutex(request.session)) {
            val verified = AccountAuthentication.recentlyVerified(account.subject, request)
            if (!verified && body.currentPassword.isNullOrEmpty()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).cacheControl(CacheControl.noStore())
                    .body(mapOf("code" to "password_verification_required"))
            }
            if (verified && !AccountAuthentication.reserveVerifiedPasswordSave(request)) {
                response.setHeader("Password-Verification-Expired", "true")
            }
            val version = passwords.save(account.subject, body.password, body.currentPassword, verified,
                request.session.getAttribute(AccountAuthentication.CREDENTIALS_VERSION) as? Long)
            request.session.setAttribute(AccountAuthentication.CREDENTIALS_VERSION, version)
            AccountAuthentication.consumeVerification(request)
            AccountAuthentication.rotateSession(request, response)
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build<Void>()
    }
}
