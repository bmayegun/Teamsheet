package com.biz.teamsheet

import com.biz.teamsheet.auth.AccountAuthentication
import com.biz.teamsheet.auth.EmailSignIn
import com.biz.teamsheet.auth.EmailSignInStore
import com.biz.teamsheet.auth.PasswordAuthentication
import com.biz.teamsheet.auth.PasswordAuthenticationController
import com.biz.teamsheet.club.ClubStore
import com.biz.teamsheet.roster.RosterController
import com.biz.teamsheet.roster.RosterSecurity
import com.biz.teamsheet.roster.RosterStore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import org.mockito.Mockito
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository

class PasswordAccountPreservationTests {
    private fun googleUser() = DefaultOidcUser(emptyList(), OidcIdToken(
        "test-token", Instant.now(), Instant.now().plusSeconds(300),
        mapOf("sub" to "google-account", "name" to "Google Profile Name"),
    ))

    @Test
    fun `setting a password preserves Google identity while rotating session and csrf token`() {
        val principal = googleUser()
        val subject = checkNotNull(principal.subject)
        val authentication = OAuth2AuthenticationToken(principal, emptyList(), "google")
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = authentication
        SecurityContextHolder.setContext(context)
        try {
            val request = MockHttpServletRequest()
            val response = MockHttpServletResponse()
            request.session
            HttpSessionSecurityContextRepository().saveContext(context, request, response)
            val initialSessionId = request.session.id
            val csrf = HttpSessionCsrfTokenRepository()
            val initialToken = csrf.generateToken(request)
            csrf.saveToken(initialToken, request, response)
            AccountAuthentication.emailVerified(subject, request)
            val passwords = Mockito.mock(PasswordAuthentication::class.java)
            val controller = PasswordAuthenticationController(passwords)
            assertEquals(204, controller.save(
                PasswordAuthenticationController.PasswordChange("new password example"), principal, request, response,
            ).statusCode.value())
            assertSame(authentication, SecurityContextHolder.getContext().authentication)
            assertSame(context, request.session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
            assertNotEquals(initialSessionId, request.session.id)
            assertNotEquals(initialToken.token, csrf.loadToken(request)?.token)
            assertFalse(AccountAuthentication.recentlyVerified(subject, request))

            val email = Mockito.mock(EmailSignIn::class.java)
            Mockito.`when`(email.identity(subject)).thenReturn(EmailSignInStore.EmailIdentity("google@example.test", true))
            val roster = RosterController(Mockito.mock(RosterStore::class.java), email, Mockito.mock(ClubStore::class.java))
            val session = roster.session(principal, csrf.loadToken(request)!!, request).body!!
            assertEquals("Google Profile Name", session.name)
            assertEquals("google", session.signInMethod)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    @Test
    fun `Google redirect continues when updating the cosmetic member name fails`() {
        val clubs = Mockito.mock(ClubStore::class.java)
        Mockito.doAnswer { throw org.springframework.dao.DataAccessResourceFailureException("test unavailable database") }.`when`(clubs)
            .updateDisplayName("google-account", "Google Profile Name")
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        RosterSecurity().googleSuccessHandler(clubs).onAuthenticationSuccess(
            request, response, OAuth2AuthenticationToken(googleUser(), emptyList(), "google"),
        )
        assertEquals(302, response.status)
        assertEquals("/", response.redirectedUrl)
    }
}
