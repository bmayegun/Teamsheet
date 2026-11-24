package com.biz.teamsheet

import com.biz.teamsheet.auth.AccountAuthentication
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertSame
import org.springframework.mock.web.MockHttpServletRequest

class PasswordVerificationTests {
    @Test
    fun `session listener supplies the same mutex through different session wrappers`() {
        val session = org.springframework.mock.web.MockHttpSession()
        org.springframework.web.util.HttpSessionMutexListener().sessionCreated(jakarta.servlet.http.HttpSessionEvent(session))
        val wrapper = org.mockito.Mockito.mock(jakarta.servlet.http.HttpSession::class.java,
            org.mockito.AdditionalAnswers.delegatesTo<jakarta.servlet.http.HttpSession>(session))
        assertSame(org.springframework.web.util.WebUtils.getSessionMutex(session),
            org.springframework.web.util.WebUtils.getSessionMutex(wrapper))
    }

    @Test
    fun `email proof is subject bound expires and can be consumed`() {
        val request = MockHttpServletRequest()
        assertFalse(AccountAuthentication.recentlyVerified("account", request))
        AccountAuthentication.emailVerified("account", request)
        assertTrue(AccountAuthentication.recentlyVerified("account", request))
        assertNotNull(AccountAuthentication.verificationExpiresAt("account", request))
        assertFalse(AccountAuthentication.recentlyVerified("other-account", request))
        assertNull(AccountAuthentication.verificationExpiresAt("other-account", request))
        request.session.setAttribute("teamsheet.emailVerifiedAt", System.currentTimeMillis() - 301_000)
        assertFalse(AccountAuthentication.recentlyVerified("account", request))
        AccountAuthentication.emailVerified("account", request)
        AccountAuthentication.consumeVerification(request)
        assertNull(AccountAuthentication.verificationExpiresAt("account", request))
        assertFalse(AccountAuthentication.recentlyVerified("account", request))
    }
}
