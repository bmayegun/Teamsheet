package com.biz.teamsheet

import com.biz.teamsheet.club.ClubStore
import com.biz.teamsheet.auth.SignInEmail
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
    @MockitoBean private lateinit var passwordSafety: com.biz.teamsheet.auth.PasswordSafety
    @MockitoBean private lateinit var resend: SignInEmail
    @Autowired private lateinit var json: JsonMapper
    @Autowired private lateinit var codes: EmailSignInStore
    @Autowired private lateinit var rosters: RosterStore
    @Autowired private lateinit var clubs: ClubStore

    private inner class Browser {
        private val cookies = CookieManager()
        private val client = HttpClient.newBuilder().cookieHandler(cookies).build()
        private var csrf: String? = null
        val id: String get() = cookies.cookieStore.cookies.single { it.name == "JSESSIONID" }.value

        fun session() = json.readTree(get("/api/session").body()).also { csrf = it.path("csrfToken").asText() }

        fun get(path: String) = send(request(path).build())

        fun put(path: String, body: Map<String, Any>, includeCsrf: Boolean = true): HttpResponse<String> {
            val builder = request(path).header("Content-Type", "application/json")
            if (includeCsrf) builder.header("X-CSRF-TOKEN", checkNotNull(csrf))
            return send(builder.PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build())
        }

        fun delete(path: String) = send(request(path).header("X-CSRF-TOKEN", checkNotNull(csrf)).DELETE().build())

        fun post(path: String, body: Map<String, Any>, includeCsrf: Boolean = true, token: String? = csrf): HttpResponse<String> {
            val builder = request(path).header("Content-Type", "application/json")
            if (includeCsrf) builder.header("X-CSRF-TOKEN", checkNotNull(token))
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
        codes.issue(email, linkId, "google-browser", subject, hash)
        assertEquals(EmailVerification.Invalid, codes.verify(linkId, "other-browser", subject, hash))
        assertEquals(EmailVerification.Invalid, codes.verify(linkId, "google-browser", null, hash))
        assertEquals(EmailVerification.Verified(subject), codes.verify(linkId, "google-browser", subject, hash))
        assertEquals(EmailVerification.Invalid, codes.verify(linkId, "google-browser", subject, hash))
        kotlin.test.assertFailsWith<com.biz.teamsheet.auth.EmailSendLimit> { codes.issue(email, UUID.randomUUID(), "google-browser", subject, hash) }

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
        assertEquals(email, browser.session().path("linkedEmail").asString())
        assertEquals("Organiser", browser.session().path("name").asText())
        assertTrue(browser.get("/api/roster").body().contains(marker))
        val clubResponse = browser.post("/api/clubs", mapOf("name" to "HTTP invitation club"))
        assertEquals(200, clubResponse.statusCode(), clubResponse.body())
        val clubId = json.readTree(clubResponse.body()).path("id").asText()
        assertEquals(200, browser.post("/api/clubs/$clubId/invitations", emptyMap()).statusCode())
        val revokeResponse = browser.delete("/api/clubs/$clubId/invitations")
        assertEquals(204, revokeResponse.statusCode(), revokeResponse.body())
        assertEquals(401, otherBrowser.get("/api/roster").statusCode())
        val password = "legacy roster password 123"
        assertEquals(204, browser.put("/api/account/password", mapOf("password" to password)).statusCode())
        otherBrowser.session()
        assertEquals(204, otherBrowser.post("/api/auth/password", mapOf("email" to email, "password" to password)).statusCode())
        assertTrue(otherBrowser.get("/api/roster").body().contains(marker))
    }

    @Test
    fun `new email organiser can preview an invitation on the first page after login`() {
        val owner = UUID.randomUUID().toString()
        val club = clubs.create(owner, "Invitation test club")
        val invitation = mapOf("token" to clubs.invite(owner, club.id))
        val sentCode = stubResend()
        val browser = Browser()
        browser.session()
        val issued = browser.post("/api/auth/email/request", mapOf("email" to ("${UUID.randomUUID()}@example.test")))
        assertEquals(200, issued.statusCode())
        val challenge = json.readTree(issued.body()).path("challengeId").asString()
        assertEquals(204, browser.post("/api/auth/email/verify", mapOf("challengeId" to challenge, "code" to sentCode.get())).statusCode())
        val firstReads = List(2) {
            java.util.concurrent.CompletableFuture.supplyAsync {
                json.readTree(browser.get("/api/session").body()).path("csrfToken").asString()
            }
        }
        val preview = "/api/clubs/invitations/preview"
        val tokens = firstReads.map { it.join() }
        assertEquals(403, browser.post(preview, invitation).statusCode()) // pre-login token
        for (token in tokens) {
            val result = browser.post(preview, invitation, token = token)
            assertEquals(200, result.statusCode())
            assertEquals(club.name, json.readTree(result.body()).path("name").asString())
        }
    }

    @Test
    fun `chosen name is used for existing and future memberships across sign-ins`() {
        val subject = "name-test-${UUID.randomUUID()}"
        val existing = clubs.create(subject, "Existing club")
        clubs.savePreferredName(subject, "  Alex  ")
        val next = clubs.create(subject, "Next club")
        clubs.updateDisplayName(subject, "Google profile name")
        assertEquals("Alex", clubs.preferredName(subject))
        for (club in listOf(existing, next)) {
            assertEquals("Alex", clubs.management(subject, club.id).members.single().name)
        }
    }

    @Test
    fun `verified email can set password login change and recover the same roster`() {
        val email = "${UUID.randomUUID()}@example.test"
        val sent = stubResend()
        val setup = Browser()
        setup.session()
        val password = "ab cdefg"
        val replacement = "a different replacement password 456"
        assertEquals(401, setup.put("/api/account/password", mapOf("password" to password)).statusCode())
        fun verify(browser: Browser) {
            val issued = browser.post("/api/auth/email/request", mapOf("email" to email))
            assertEquals(200, issued.statusCode())
            val id = json.readTree(issued.body()).path("challengeId").asString()
            assertEquals(204, browser.post("/api/auth/email/verify", mapOf("challengeId" to id, "code" to sent.get())).statusCode())
            browser.session()
        }
        verify(setup)
        assertEquals(400, setup.put("/api/account/password", mapOf("password" to "short")).statusCode())
        assertEquals(400, setup.put("/api/account/password", mapOf("password" to "abcdefg")).statusCode())
        org.mockito.Mockito.doThrow(org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY))
            .`when`(passwordSafety).check("Password1!")
        assertEquals(422, setup.put("/api/account/password", mapOf("password" to "Password1!")).statusCode())
        assertEquals(204, setup.put("/api/account/password", mapOf("password" to password)).statusCode())
        assertTrue(setup.session().path("hasPassword").asBoolean())
        assertTrue(setup.session().path("passwordVerificationExpiresAt").isNull)
        val marker = "Preserved across password login"
        val roster = mapOf("revision" to 0, "draft" to mapOf("players" to listOf(
            mapOf("id" to "password-test", "name" to marker, "skillRating" to 3, "positions" to emptyList<String>())
        ), "attendingIds" to emptyList<String>(), "guests" to emptyList<String>()))
        assertEquals(200, setup.put("/api/roster", roster).statusCode())

        val login = Browser()
        login.session()
        val initial = login.id
        assertEquals(403, login.post("/api/auth/password", mapOf("email" to email, "password" to password), false).statusCode())
        assertEquals(401, login.post("/api/auth/password", mapOf("email" to email, "password" to "wrong")).statusCode())
        assertEquals(204, login.post("/api/auth/password", mapOf("email" to email.uppercase(), "password" to password)).statusCode())
        assertNotEquals(initial, login.id)
        assertEquals("password", login.session().path("signInMethod").asString())
        assertTrue(login.get("/api/roster").body().contains(marker))
        assertEquals(403, login.put("/api/account/password", mapOf("password" to replacement)).statusCode())
        assertEquals(403, login.put("/api/account/password", mapOf("password" to replacement, "currentPassword" to "wrong")).statusCode())
        assertEquals(204, login.put("/api/account/password", mapOf("password" to replacement, "currentPassword" to password)).statusCode())

        ageCode(email)
        val recovery = Browser()
        recovery.session()
        verify(recovery)
        assertTrue(recovery.get("/api/roster").body().contains(marker))
        assertEquals(204, recovery.put("/api/account/password", mapOf("password" to password)).statusCode())
        recovery.session()
        // The email proof was consumed by the reset, so changing again needs fresh proof.
        val proofRequired = recovery.put("/api/account/password", mapOf("password" to replacement))
        assertEquals(403, proofRequired.statusCode())
        assertEquals("password_verification_required", json.readTree(proofRequired.body()).path("code").asString())
        agePasswordAttempts(email)
        val finalLogin = Browser()
        finalLogin.session()
        assertEquals(401, finalLogin.post("/api/auth/password", mapOf("email" to email, "password" to replacement)).statusCode())
        assertEquals(204, finalLogin.post("/api/auth/password", mapOf("email" to email, "password" to password)).statusCode())
    }

    @Test
    fun `concurrent password setup consumes email proof only once`() {
        val browser = Browser()
        browser.session()
        val sent = stubResend()
        val issued = browser.post("/api/auth/email/request", mapOf("email" to "${UUID.randomUUID()}@example.test"))
        assertEquals(200, issued.statusCode())
        val id = json.readTree(issued.body()).path("challengeId").asString()
        assertEquals(204, browser.post("/api/auth/email/verify", mapOf("challengeId" to id, "code" to sent.get())).statusCode())
        browser.session()
        val requests = List(2) { index ->
            java.util.concurrent.CompletableFuture.supplyAsync {
                browser.put("/api/account/password", mapOf("password" to "concurrent password $index")).statusCode()
            }
        }
        assertEquals(listOf(204, 403), requests.map { it.join() }.sorted())
    }

    @Test
    fun `password guessing has a persistent limit for unknown addresses too`() {
        val browser = Browser()
        browser.session()
        val body = mapOf("email" to "${UUID.randomUUID()}@example.test", "password" to "wrong password")
        repeat(10) { assertEquals(401, browser.post("/api/auth/password", body).statusCode()) }
        assertEquals(429, browser.post("/api/auth/password", body).statusCode())
    }

    private fun agePasswordAttempts(email: String) {
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(email.toByteArray())
            .joinToString("") { "%02x".format(it) }
        java.sql.DriverManager.getConnection(checkNotNull(System.getenv("TEAMSHEET_AUTH_TEST_JDBC")), testUsername(), testPassword()).use { c ->
            c.prepareStatement("UPDATE password_sign_in_limits SET started_at = now() - interval '16 minutes' WHERE bucket = ?").use {
                it.setString(1, key)
                it.executeUpdate()
            }
        }
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

    @Test
    fun `personal workspace names persist independently of rosters and other accounts`() {
        val subject = "workspace-${UUID.randomUUID()}"
        val other = "workspace-${UUID.randomUUID()}"
        assertEquals("My players", clubs.personalName(subject))
        assertEquals("Sunday squad", clubs.renamePersonal(subject, "  Sunday squad  "))
        assertEquals("Sunday squad", clubs.personalName(subject))
        assertEquals("My players", clubs.personalName(other))
        assertEquals("My team", clubs.renamePersonal(subject, "My team"))
        assertEquals("My team", clubs.personalName(subject))
        kotlin.test.assertFailsWith<org.springframework.web.server.ResponseStatusException> { clubs.renamePersonal(subject, "  ") }
        kotlin.test.assertFailsWith<org.springframework.web.server.ResponseStatusException> { clubs.renamePersonal(subject, "x".repeat(101)) }
        assertEquals("My team", clubs.personalName(subject))
        assertEquals(RosterDraft(), rosters.read(subject).draft)
    }

    @Test
    fun `owners manage club access and revoked or consumed invitations cannot restore removed members`() {
        val owner = "owner-${UUID.randomUUID()}"
        val organiser = "organiser-${UUID.randomUUID()}"
        val outsider = "outsider-${UUID.randomUUID()}"
        val club = clubs.create(owner, "Club before rename")
        val unused = clubs.invite(owner, club.id)
        assertEquals("Club before rename", clubs.preview(unused).name)
        assertEquals("ORGANISER", clubs.preview(unused).role)
        assertTrue(clubs.management(owner, club.id).invitationExpiresAt != null)
        clubs.revokeInvitations(owner, club.id)
        kotlin.test.assertFailsWith<org.springframework.web.server.ResponseStatusException> { clubs.accept(organiser, unused) }
        kotlin.test.assertFailsWith<org.springframework.web.server.ResponseStatusException> { clubs.preview(unused) }
        assertEquals(null, clubs.management(owner, club.id).invitationExpiresAt)

        val invite = clubs.invite(owner, club.id)
        assertEquals("Renamed Club", clubs.rename(owner, club.id, " Renamed Club ").name)
        assertEquals("Renamed Club", clubs.preview(invite).name)
        clubs.accept(organiser, invite)
        clubs.updateDisplayName(owner, "Club Owner")
        clubs.updateDisplayName(organiser, "Invited Organiser")
        val members = clubs.management(owner, club.id).members
        assertEquals(2, members.size)
        assertEquals("Club Owner", members.single { it.isYou }.name)
        val member = members.single { it.role == "ORGANISER" }
        assertEquals("Invited Organiser", member.name)
        assertEquals("club:${club.id}", clubs.withRosterAccess(organiser, club.id) { it })
        kotlin.test.assertFailsWith<org.springframework.web.server.ResponseStatusException> { clubs.management(outsider, club.id) }
        kotlin.test.assertFailsWith<org.springframework.web.server.ResponseStatusException> { clubs.rename(organiser, club.id, "Wrong") }
        kotlin.test.assertFailsWith<org.springframework.web.server.ResponseStatusException> { clubs.revokeInvitations(organiser, club.id) }
        kotlin.test.assertFailsWith<org.springframework.web.server.ResponseStatusException> { clubs.removeMember(organiser, club.id, member.id) }
        kotlin.test.assertFailsWith<org.springframework.web.server.ResponseStatusException> { clubs.removeMember(owner, club.id, members.single { it.isYou }.id) }
        clubs.removeMember(owner, club.id, member.id)
        assertEquals(1, clubs.management(owner, club.id).members.size)
        kotlin.test.assertFailsWith<org.springframework.web.server.ResponseStatusException> { clubs.withRosterAccess(organiser, club.id) { error("Removed member should not reach the roster") } }
        kotlin.test.assertFailsWith<org.springframework.web.server.ResponseStatusException> { clubs.accept(organiser, invite) }
    }

    @Test
    fun `club membership shares a roster while personal rosters remain separate`() {
        val owner = "owner-${UUID.randomUUID()}"
        val organiser = "organiser-${UUID.randomUUID()}"
        val stranger = "stranger-${UUID.randomUUID()}"
        val club = clubs.create(owner, " Saturday Football ")
        assertEquals("Saturday Football", club.name)
        assertEquals("OWNER", club.role)
        kotlin.test.assertFailsWith<org.springframework.web.server.ResponseStatusException> { clubs.rosterSubject(stranger, club.id) }
        val subject = clubs.rosterSubject(owner, club.id)
        val roster = SavedRoster(0, RosterDraft(players = listOf(Player("club-player", "Club player", 4))))
        assertTrue(rosters.save(subject, roster))
        val firstInvite = clubs.invite(owner, club.id)
        val replacement = clubs.invite(owner, club.id)
        kotlin.test.assertFailsWith<org.springframework.web.server.ResponseStatusException> { clubs.accept(organiser, firstInvite) }
        assertEquals("ORGANISER", clubs.accept(organiser, replacement).role)
        assertEquals(clubs.rosterSubject(owner, club.id), clubs.rosterSubject(organiser, club.id))
        assertEquals(roster.draft, rosters.read(clubs.rosterSubject(organiser, club.id)).draft)
        assertEquals(RosterDraft(), rosters.read(organiser).draft)
        assertEquals(club.id, clubs.accept(organiser, replacement).id)
        kotlin.test.assertFailsWith<org.springframework.web.server.ResponseStatusException> { clubs.accept(stranger, replacement) }
        kotlin.test.assertFailsWith<org.springframework.web.server.ResponseStatusException> { clubs.invite(organiser, club.id) }
        assertEquals(listOf(club.id), clubs.list(organiser).map { it.id })
        assertEquals(emptyList(), clubs.list(stranger))
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
