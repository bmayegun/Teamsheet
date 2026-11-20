package com.biz.teamsheet

import com.biz.teamsheet.auth.ResendEmail
import com.biz.teamsheet.auth.EmailSignInStore
import com.biz.teamsheet.auth.EmailVerification
import com.biz.teamsheet.model.Player
import com.biz.teamsheet.roster.RosterDraft
import com.biz.teamsheet.roster.RosterStore
import com.biz.teamsheet.roster.SavedRoster
import java.net.CookieManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import tools.jackson.databind.json.JsonMapper

@EnabledIfEnvironmentVariable(named = "TEAMSHEET_AUTH_TEST_JDBC", matches = ".+")
@ActiveProfiles("roster")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "GOOGLE_CLIENT_ID=test-client",
    "GOOGLE_CLIENT_SECRET=test-secret",
    "GOOGLE_REDIRECT_URI=http://localhost/login/oauth2/code/google",
    "ROSTER_SECURE_COOKIE=false",
    "EMAIL_CODE_SECRET=test-secret-for-the-email-code-flow-only",
])
class EmailSignInApiTests {
    @LocalServerPort private var port = 0
    @MockitoBean private lateinit var resend: ResendEmail
    @Autowired private lateinit var json: JsonMapper
    @Autowired private lateinit var codes: EmailSignInStore
    @Autowired private lateinit var rosters: RosterStore

    private inner class Browser {
        private val cookies = CookieManager()
        private val client = HttpClient.newBuilder().cookieHandler(cookies).build()
        private var csrf: String? = null
        val id: String get() = cookies.cookieStore.cookies.single { it.name == "JSESSIONID" }.value

        fun session() = json.readTree(get("/api/session").body()).also { csrf = it.path("csrfToken").asText() }

        fun get(path: String) = send(request(path).build())

        fun post(path: String, body: Map<String, Any>, includeCsrf: Boolean = true): HttpResponse<String> {
            val builder = request(path).header("Content-Type", "application/json")
            if (includeCsrf) builder.header("X-CSRF-TOKEN", checkNotNull(csrf))
            return send(builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build())
        }

        private fun request(path: String) = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
        private fun send(request: HttpRequest) = client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `email authentication opens the linked legacy roster and rotates the session`() {
        val subject = "test-google-${UUID.randomUUID()}"
        val email = "${UUID.randomUUID()}@example.com"
        val marker = "Player on the Google roster"
        assertTrue(rosters.save(subject, SavedRoster(0, RosterDraft(players = listOf(Player("p", marker, 4))))))

        val linkId = UUID.randomUUID()
        val hash = byteArrayOf(1, 2, 3)
        assertTrue(codes.issue(email, linkId, "google-browser", subject, hash))
        assertEquals(EmailVerification.Invalid, codes.verify(linkId, "other-browser", subject, hash))
        assertEquals(EmailVerification.Invalid, codes.verify(linkId, "google-browser", null, hash))
        assertEquals(EmailVerification.Verified(subject), codes.verify(linkId, "google-browser", subject, hash))
        assertEquals(EmailVerification.Invalid, codes.verify(linkId, "google-browser", subject, hash))
        assertTrue(!codes.issue(email, UUID.randomUUID(), "google-browser", subject, hash))

        ageCode(email)
        val browser = Browser()
        val sentCode = stubResend()
        assertTrue(browser.session().path("emailSignInEnabled").asBoolean())
        val initialId = browser.id
        assertEquals(403, browser.post("/api/auth/email/request", mapOf("email" to email), false).statusCode())
        assertEquals(401, browser.post("/api/auth/email/request", mapOf("email" to email, "linking" to true)).statusCode())
        val issued = browser.post("/api/auth/email/request", mapOf("email" to email.uppercase()))
        assertEquals(200, issued.statusCode(), issued.body())
        val id = json.readTree(issued.body()).path("challengeId").asText()
        assertEquals(429, browser.post("/api/auth/email/request", mapOf("email" to email)).statusCode())
        val verification = mapOf("challengeId" to id, "code" to sentCode.get())
        val otherBrowser = Browser()
        otherBrowser.session()
        assertEquals(400, otherBrowser.post("/api/auth/email/verify", verification).statusCode())
        assertEquals(204, browser.post("/api/auth/email/verify", verification).statusCode())
        assertNotEquals(initialId, browser.id)
        assertEquals("Organiser", browser.session().path("name").asText())
        assertTrue(browser.get("/api/roster").body().contains(marker))
        assertEquals(401, otherBrowser.get("/api/roster").statusCode())
    }

    @Test
    fun `five wrong guesses exhaust a code even if the next guess is correct`() {
        val email = "${UUID.randomUUID()}@example.com"
        val sentCode = stubResend()
        val browser = Browser()
        browser.session()
        val response = browser.post("/api/auth/email/request", mapOf("email" to email))
        assertEquals(200, response.statusCode())
        val challengeId = json.readTree(response.body()).path("challengeId").asText()
        val currentCode = sentCode.get()
        val wrong = if (currentCode == "000000") "000001" else "000000"
        repeat(5) {
            assertEquals(400, browser.post("/api/auth/email/verify", mapOf("challengeId" to challengeId, "code" to wrong)).statusCode())
        }
        assertEquals(400, browser.post("/api/auth/email/verify", mapOf("challengeId" to challengeId, "code" to currentCode)).statusCode())
        assertEquals(401, browser.get("/api/roster").statusCode())
    }

    private fun stubResend(): AtomicReference<String> {
        val code = AtomicReference<String>()
        Mockito.`when`(resend.configured).thenReturn(true)
        Mockito.doAnswer {
            code.set(it.getArgument<String>(1))
            null
        }.`when`(resend).sendCode(anyString(), anyString(), anyBoolean())
        return code
    }

    private fun ageCode(email: String) {
        val url = checkNotNull(System.getenv("TEAMSHEET_AUTH_TEST_JDBC"))
        java.sql.DriverManager.getConnection(url, testUsername(), testPassword()).use { connection ->
            connection.prepareStatement("UPDATE email_sign_in_codes SET issued_at = now() - interval '11 minutes' WHERE email = ?").use {
                it.setString(1, email)
                assertEquals(1, it.executeUpdate())
            }
        }
    }

    companion object {
        private fun testUsername() = System.getenv("TEAMSHEET_AUTH_TEST_USER") ?: System.getProperty("user.name")
        private fun testPassword() = System.getenv("TEAMSHEET_AUTH_TEST_PASSWORD") ?: ""

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("teamsheet.roster.jdbc-url") { System.getenv("TEAMSHEET_AUTH_TEST_JDBC") }
            registry.add("teamsheet.roster.username") { testUsername() }
            registry.add("teamsheet.roster.password") { testPassword() }
        }
    }
}
