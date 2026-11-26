package com.biz.teamsheet

import com.biz.teamsheet.auth.AccountAuthentication
import com.biz.teamsheet.auth.CredentialsVersionFilter
import com.biz.teamsheet.auth.EmailSignInStore
import com.biz.teamsheet.auth.PasswordAuthentication
import com.biz.teamsheet.auth.PasswordSafety
import com.biz.teamsheet.auth.RosterAccount
import jakarta.servlet.FilterChain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.mockito.Mockito
import org.springframework.mock.web.MockHttpSession
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException

class CredentialsVersionTests {
    @Test
    fun `old and unversioned sessions lose authentication while current sessions survive`() {
        val identities = Mockito.mock(EmailSignInStore::class.java)
        Mockito.`when`(identities.credentialsVersion("account")).thenReturn(2L)
        for (version in listOf(null, 1L, 2L)) {
            val request = MockHttpServletRequest()
            val session = request.session as MockHttpSession
            if (version != null) session.setAttribute(AccountAuthentication.CREDENTIALS_VERSION, version)
            val authentication = UsernamePasswordAuthenticationToken.authenticated(
                RosterAccount("account", "Organiser"), null, emptyList())
            val context = SecurityContextHolder.createEmptyContext()
            context.authentication = authentication
            SecurityContextHolder.setContext(context)
            try {
                var reached = false
                CredentialsVersionFilter(identities).doFilter(request, MockHttpServletResponse(), FilterChain { _, _ ->
                    reached = true
                    if (version == 2L) {
                        assertSame(authentication, SecurityContextHolder.getContext().authentication)
                        assertFalse(session.isInvalid)
                    } else {
                        assertNull(SecurityContextHolder.getContext().authentication)
                        assertTrue(session.isInvalid)
                    }
                })
                assertTrue(reached)
            } finally {
                SecurityContextHolder.clearContext()
            }
        }
    }

    @Test
    fun `reset cannot use a verification proof from an older credentials version`() {
        val identities = Mockito.mock(EmailSignInStore::class.java)
        val safety = Mockito.mock(PasswordSafety::class.java)
        Mockito.`when`(identities.linkedEmail("account")).thenReturn("account@example.test")
        Mockito.`when`(identities.credentials("account@example.test"))
            .thenReturn(EmailSignInStore.Credentials("account", null, 2L))
        val error = assertFailsWith<ResponseStatusException> {
            PasswordAuthentication(identities, safety).save("account", "new safe password", null, true, 1L)
        }
        assertEquals(401, error.statusCode.value())
        Mockito.verifyNoInteractions(safety)
    }
}
