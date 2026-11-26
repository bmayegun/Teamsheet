package com.biz.teamsheet

import com.biz.teamsheet.auth.AuthenticationNetworkLimits
import com.biz.teamsheet.auth.EmailSignInStore
import com.biz.teamsheet.club.ClubStore
import com.biz.teamsheet.model.Player
import com.biz.teamsheet.roster.RosterController
import com.biz.teamsheet.roster.RosterDraft
import com.biz.teamsheet.roster.RosterStore
import com.biz.teamsheet.roster.SavedRoster
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.csrf.DefaultCsrfToken
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.server.ResponseStatusException

@ActiveProfiles("roster")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    "teamsheet.roster.database.enabled=false",
    "GOOGLE_CLIENT_ID=test-client",
    "GOOGLE_CLIENT_SECRET=test-secret",
    "GOOGLE_REDIRECT_URI=http://localhost/login/oauth2/code/google",
])
class RosterApiTests {
    @LocalServerPort
    private var port = 0

    @MockitoBean
    private lateinit var store: RosterStore

    @MockitoBean
    private lateinit var emailStore: EmailSignInStore

    @MockitoBean
    private lateinit var clubs: ClubStore

    @MockitoBean
    private lateinit var networkLimits: AuthenticationNetworkLimits

    @Autowired
    private lateinit var controller: RosterController

    @Test
    fun `reading account status does not update member names`() {
        val principal = Mockito.mock(OidcUser::class.java)
        Mockito.`when`(principal.subject).thenReturn("read-only-session")
        Mockito.`when`(principal.fullName).thenReturn("Google Name")
        Mockito.clearInvocations(clubs)
        val csrf = DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "test-token")
        val request = MockHttpServletRequest()
        repeat(2) {
            assertEquals("Google Name", controller.session(principal, csrf, request).body?.name)
        }
        Mockito.verify(clubs, Mockito.never()).updateDisplayName(Mockito.anyString(), Mockito.anyString())
    }

    @Test
    fun `private roster requires sign-in while Google login can be started`() {
        val client = HttpClient.newHttpClient()
        fun get(path: String) = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(401, get("/api/roster").statusCode())
        assertEquals(401, get("/api/account/unknown").statusCode())
        val session = get("/api/session")
        assertEquals(200, session.statusCode())
        assertTrue(session.body().contains("\"name\":null"))
        val login = get("/oauth2/authorization/google")
        assertEquals(302, login.statusCode())
        assertTrue(login.headers().firstValue("Location").orElseThrow().startsWith("https://accounts.google.com/"))
    }

    @Test
    fun `saves are scoped to the signed-in subject and stale revisions are refused`() {
        val account = Mockito.mock(OidcUser::class.java)
        Mockito.`when`(account.subject).thenReturn("organiser-a")
        val roster = SavedRoster(2, RosterDraft(
            players = listOf(Player("player-a", "Registered player", 4)),
            attendingIds = setOf("player-a"),
            guests = listOf(Player("guest-a", "Guest", 3)),
        ))
        Mockito.`when`(store.save("organiser-a", roster)).thenReturn(true, false)
        assertEquals(3, controller.save(account, roster).body?.revision)
        val conflict = assertFailsWith<ResponseStatusException> { controller.save(account, roster) }
        assertEquals(HttpStatus.CONFLICT, conflict.statusCode)
    }
}
