package com.biz.teamsheet.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository

internal object AccountAuthentication {
    private const val EMAIL_VERIFIED_AT = "teamsheet.emailVerifiedAt"
    private const val PASSWORD_SAVE_ATTEMPTS = "teamsheet.verifiedPasswordSaveAttempts"
    private const val EMAIL_VERIFIED_SUBJECT = "teamsheet.emailVerifiedSubject"

    fun rotateSession(request: HttpServletRequest, response: HttpServletResponse) {
        request.changeSessionId()
        val csrf = HttpSessionCsrfTokenRepository()
        csrf.saveToken(csrf.generateToken(request), request, response)
    }

    fun signIn(subject: String, method: String, request: HttpServletRequest, response: HttpServletResponse) {
        rotateSession(request, response)
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = UsernamePasswordAuthenticationToken.authenticated(
            RosterAccount(subject, "Organiser", method), null, emptyList())
        SecurityContextHolder.setContext(context)
        HttpSessionSecurityContextRepository().saveContext(context, request, response)
    }

    fun emailVerified(subject: String, request: HttpServletRequest) {
        request.session.setAttribute(PASSWORD_SAVE_ATTEMPTS, 0)
        request.session.setAttribute(EMAIL_VERIFIED_SUBJECT, subject)
        request.session.setAttribute(EMAIL_VERIFIED_AT, System.currentTimeMillis())
    }

    fun verificationExpiresAt(subject: String, request: HttpServletRequest): Long? {
        val session = request.getSession(false) ?: return null
        if (session.getAttribute(EMAIL_VERIFIED_SUBJECT) != subject) return null
        val time = session.getAttribute(EMAIL_VERIFIED_AT) as? Long ?: return null
        return (time + 300_000).takeIf { System.currentTimeMillis() - time in 0..300_000 }
    }

    fun recentlyVerified(subject: String, request: HttpServletRequest): Boolean =
        verificationExpiresAt(subject, request) != null

    fun reserveVerifiedPasswordSave(request: HttpServletRequest): Boolean {
        val attempts = (request.session.getAttribute(PASSWORD_SAVE_ATTEMPTS) as? Int ?: 0) + 1
        request.session.setAttribute(PASSWORD_SAVE_ATTEMPTS, attempts)
        if (attempts >= 3) {
            consumeVerification(request)
            return false
        }
        return true
    }

    fun consumeVerification(request: HttpServletRequest) {
        request.session.removeAttribute(PASSWORD_SAVE_ATTEMPTS)
        request.session.removeAttribute(EMAIL_VERIFIED_AT)
        request.session.removeAttribute(EMAIL_VERIFIED_SUBJECT)
    }
}
