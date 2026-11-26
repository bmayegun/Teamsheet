package com.biz.teamsheet.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.WebUtils

internal class CredentialsVersionFilter(private val identities: EmailSignInStore) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val session = request.getSession(false)
        if (session == null) {
            chain.doFilter(request, response)
            return
        }
        synchronized(WebUtils.getSessionMutex(session)) {
            val account = rosterAccount(SecurityContextHolder.getContext().authentication?.principal)
            if (account != null) {
                // Existing sessions predate version tracking and must sign in again.
                val version = session.getAttribute(AccountAuthentication.CREDENTIALS_VERSION) as? Long
                if (version == null || version != identities.credentialsVersion(account.subject)) {
                    SecurityContextHolder.clearContext()
                    session.invalidate()
                }
            }
            chain.doFilter(request, response)
        }
    }
}
