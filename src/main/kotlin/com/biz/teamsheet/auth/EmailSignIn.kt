package com.biz.teamsheet.auth

import java.io.IOException
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

internal class EmailSendLimit(val daily: Boolean, val waitSeconds: Int = 60) : RuntimeException()

data class RosterAccount(val subject: String, val displayName: String, val method: String = "email")

internal fun rosterAccount(principal: Any?): RosterAccount? = when (principal) {
    is RosterAccount -> principal
    is OidcUser -> principal.subject?.let { RosterAccount(it, principal.fullName ?: "Organiser") }
    else -> null
}

@Service
@Profile("roster")
class EmailSignIn(
    private val store: EmailSignInStore,
    private val mailer: SignInEmail,
    @Value("\${teamsheet.email.code-secret}") private val secret: String,
) {
    private val random = SecureRandom()
    private val logger = LoggerFactory.getLogger(javaClass)
    private val addressPattern = Regex("[A-Za-z0-9_+.!%'-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+")
    val enabled: Boolean get() = mailer.configured && secret.length >= 32

    internal fun identity(subject: String) = store.identity(subject)

    internal fun requestCode(address: String, browser: String, account: String?): UUID {
        val email = address.trim().lowercase(Locale.ROOT)
        if (email.length > 254 || !addressPattern.matches(email)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid email address.")
        }
        val id = UUID.randomUUID()
        val code = random.nextInt(1_000_000).toString().padStart(6, '0')
        store.issue(email, id, browser, account, hash(id, code))
        try {
            mailer.sendCode(email, code, account != null)
        } catch (error: IOException) {
            // The provider body can contain request details. Log only the failure type.
            logger.warn("Email delivery could not send a sign-in code ({})", error.javaClass.simpleName)
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Could not send a code. Try again in a minute.")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Could not send a code. Try again in a minute.")
        }
        return id
    }

    internal fun verify(id: UUID, code: String, browser: String, account: String?): String {
        if (code.length != 6 || code.any { it !in '0'..'9' }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter the six-digit code.")
        }
        return when (val verification = store.verify(id, browser, account, hash(id, code))) {
            is EmailVerification.Verified -> verification.subject
            EmailVerification.Invalid ->
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "That code is invalid or expired.")
            EmailVerification.AlreadyLinked ->
                throw ResponseStatusException(HttpStatus.CONFLICT, "This email or account already has a sign-in link.")
        }
    }

    private fun hash(id: UUID, code: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        doFinal("$id:$code".toByteArray(Charsets.UTF_8))
    }
}
