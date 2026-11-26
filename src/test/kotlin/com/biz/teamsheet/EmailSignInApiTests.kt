package com.biz.teamsheet

import com.biz.teamsheet.auth.AuthenticationNetworkLimits
import com.biz.teamsheet.auth.EmailSendLimit
import com.biz.teamsheet.auth.EmailSignInStore
import com.biz.teamsheet.auth.EmailVerification
import com.biz.teamsheet.auth.NetworkLimit
import com.biz.teamsheet.auth.PasswordSafety
import com.biz.teamsheet.auth.SignInEmail
import com.biz.teamsheet.club.ClubStore
import com.biz.teamsheet.model.Player
import com.biz.teamsheet.roster.RosterDraft
import com.biz.teamsheet.roster.RosterStore
import com.biz.teamsheet.roster.SavedRoster
import com.zaxxer.hikari.HikariDataSource
import java.net.CookieManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.json.JsonMapper

@EnabledIfEnvironmentVariable(named = "TEAMSHEET_AUTH_TEST_JDBC", matches = ".+")
@ActiveProfiles("roster")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "AUTH_TRUSTED_PROXY_CIDRS=127.0.0.1/32",
    "GOOGLE_CLIENT_ID=test-client",
    "GOOGLE_CLIENT_SECRET=test-secret",
    "GOOGLE_REDIRECT_URI=http://localhost/login/oauth2/code/google",
    "ROSTER_SECURE_COOKIE=false",
    "EMAIL_CODE_SECRET=test-secret-for-the-email-code-flow-only",
])
class EmailSignInApiTests {
    @LocalServerPort private var port = 0
    @MockitoBean private lateinit var passwordSafety: PasswordSafety
    @MockitoBean private lateinit var resend: SignInEmail
    @Autowired private lateinit var json: JsonMapper
    @Autowired private lateinit var codes: EmailSignInStore
    @Autowired private lateinit var rosters: RosterStore
    @Autowired private lateinit var clubs: ClubStore
    @Autowired private lateinit var networkLimits: AuthenticationNetworkLimits

    private inner class Browser(val address: String = "198.18.0.${browserAddresses.incrementAndGet()}") {
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

        private fun request(path: String) = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).header("X-Forwarded-For", address)
        private fun send(request: HttpRequest) = client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Autowired private lateinit var dataSource: DataSource

    @Test
    fun `network quotas isolate clients and cannot be bypassed by changing email addresses`() {
        val address = UUID.randomUUID().toString()
        repeat(10) { networkLimits.check("email-send", address) }
        assertFailsWith<NetworkLimit> { networkLimits.check("email-send", address) }
        networkLimits.check("email-send", "different-$address")
        repeat(60) { networkLimits.check("password-login", address) }
        assertFailsWith<NetworkLimit> { networkLimits.check("password-login", address) }
        networkLimits.check("password-login", "different-$address")
    }

    @Test
    fun `network email limits are atomic and daily budget survives short window resets`() {
        val address = UUID.randomUUID().toString()
        val requests = List(20) {
            CompletableFuture.supplyAsync {
                try {
                    networkLimits.check("email-send", address)
                    true
                } catch (_: NetworkLimit) {
                    false
                }
            }
        }
        assertEquals(10, requests.count { it.join() })
        val key = MessageDigest.getInstance("SHA-256").digest(address.toByteArray())
            .joinToString("") { "%02x".format(it) }
        fun expire(action: String) {
            dataSource.connection.use { connection ->
                connection.prepareStatement("UPDATE authentication_network_limits SET expires_at = now() - interval '1 second' WHERE bucket = ?").use {
                    it.setString(1, "$action:$key")
                    assertEquals(1, it.executeUpdate())
                }
            }
        }
        repeat(4) {
            expire("email-send")
            repeat(10) { networkLimits.check("email-send", address) }
        }
        expire("email-send")
        val daily = assertFailsWith<NetworkLimit> { networkLimits.check("email-send", address) }
        assertTrue(daily.daily)
        assertTrue(daily.waitSeconds in 1..86400)
        // Rejecting the daily budget must roll back the short-window reservation too.
        expire("email-send-daily")
        repeat(10) { networkLimits.check("email-send", address) }
        assertTrue(!assertFailsWith<NetworkLimit> { networkLimits.check("email-send", address) }.daily)
    }

    @Test
    fun `network password limit is enforced by the HTTP filter`() {
        val browser = Browser()
        browser.session()
        val address = "198.18.0.250"
        repeat(60) { networkLimits.check("password-login", address) }
        val blocked = Browser(address)
        blocked.session()
        val result = blocked.post("/api/auth/password", mapOf("email" to "${UUID.randomUUID()}@example.test", "password" to "wrong"))
        assertEquals(429, result.statusCode())
        assertEquals("authentication_network_limit", json.readTree(result.body()).path("code").asString())
        assertTrue(result.headers().firstValue("Retry-After").isPresent)
    }

    @Test
    fun `verified recovery caps rejected password checks and requires fresh proof`() {
        val browser = Browser()
        browser.session()
        val sent = stubResend()
        val email = "${UUID.randomUUID()}@example.test"
        val challenge = browser.post("/api/auth/email/request", mapOf("email" to email))
        val id = json.readTree(challenge.body()).path("challengeId").asString()
        assertEquals(204, browser.post("/api/auth/email/verify", mapOf("challengeId" to id, "code" to sent.get())).statusCode())
        browser.session()
        val breached = "a breached password example"
        Mockito.doThrow(ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY))
            .`when`(passwordSafety).check(breached)
        repeat(3) { assertEquals(422, browser.put("/api/account/password", mapOf("password" to breached)).statusCode()) }
        assertEquals(403, browser.put("/api/account/password", mapOf("password" to breached)).statusCode())
        Mockito.verify(passwordSafety, Mockito.times(3)).check(breached)
        assertTrue(browser.session().path("passwordVerificationExpiresAt").isNull)
        ageCode(email)
        val again = browser.post("/api/auth/email/request", mapOf("email" to email, "linking" to true))
        val nextId = json.readTree(again.body()).path("challengeId").asString()
        assertEquals(204, browser.post("/api/auth/email/verify", mapOf("challengeId" to nextId, "code" to sent.get(), "linking" to true)).statusCode())
        assertEquals(204, browser.put("/api/account/password", mapOf("password" to "a fresh safe password")).statusCode())
    }

    @Test
    fun `member removal waits for a pooled roster save to finish`() {
        val owner = "owner-${UUID.randomUUID()}"
        val member = "member-${UUID.randomUUID()}"
        val club = clubs.create(owner, "Locked club")
        clubs.accept(member, clubs.invite(owner, club.id))
        val memberId = clubs.management(owner, club.id).members.single { !it.isYou }.id
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val removing = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val save = executor.submit<Boolean> {
                clubs.withRosterAccess(member, club.id) { subject ->
                    locked.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    rosters.save(subject, SavedRoster(0, RosterDraft()))
                }
            }
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            val removal = executor.submit {
                removing.countDown()
                clubs.removeMember(owner, club.id, memberId)
            }
            assertTrue(removing.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> {
                removal.get(200, TimeUnit.MILLISECONDS)
            }
            release.countDown()
            assertTrue(save.get(5, TimeUnit.SECONDS))
            removal.get(5, TimeUnit.SECONDS)
            assertEquals(1, rosters.read("club:${club.id}").revision)
            assertFailsWith<ResponseStatusException> {
                clubs.withRosterAccess(member, club.id) { rosters.read(it) }
            }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `pooled roster operations share a transaction and roll back together`() {
        val pool = dataSource as HikariDataSource
        val owner = "pooled-${UUID.randomUUID()}"
        val club = clubs.create(owner, "Pooled club")
        val subject = "club:${club.id}"
        assertFailsWith<IllegalStateException> {
            clubs.withRosterAccess(owner, club.id) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
                assertTrue(rosters.save(subject, SavedRoster(0, RosterDraft(players = listOf(Player("p", "Rolled back", 3))))))
                assertEquals(1, pool.hikariPoolMXBean.activeConnections)
                throw IllegalStateException("rollback the entire operation")
            }
        }
        assertEquals(0, rosters.read(subject).revision)
        assertTrue(rosters.read(subject).draft.players.isEmpty())
        assertNull(clubs.withRosterAccess(owner, club.id) { null })
        repeat(20) { rosters.read(subject) }
        assertEquals(0, pool.hikariPoolMXBean.activeConnections)
        assertTrue(pool.hikariPoolMXBean.totalConnections <= pool.maximumPoolSize)
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
        assertFailsWith<EmailSendLimit> { codes.issue(email, UUID.randomUUID(), "google-browser", subject, hash) }

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
            CompletableFuture.supplyAsync {
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
        Mockito.doThrow(ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY))
            .`when`(passwordSafety).check("Password1!")
        assertEquals(422, setup.put("/api/account/password", mapOf("password" to "Password1!")).statusCode())
        ageCode(email)
        val renewed = setup.post("/api/auth/email/request", mapOf("email" to email, "linking" to true))
        val renewedId = json.readTree(renewed.body()).path("challengeId").asString()
        assertEquals(204, setup.post("/api/auth/email/verify", mapOf("challengeId" to renewedId, "code" to sent.get(), "linking" to true)).statusCode())
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

        assertEquals(401, setup.get("/api/roster").statusCode())
        assertEquals(200, login.get("/api/roster").statusCode())
        login.session()

        ageCode(email)
        val recovery = Browser()
        recovery.session()
        verify(recovery)
        assertTrue(recovery.get("/api/roster").body().contains(marker))
        assertEquals(204, recovery.put("/api/account/password", mapOf("password" to password)).statusCode())
        assertEquals(401, login.get("/api/roster").statusCode())
        assertEquals(200, recovery.get("/api/roster").statusCode())
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
            CompletableFuture.supplyAsync {
                browser.put("/api/account/password", mapOf("password" to "concurrent password $index")).statusCode()
            }
        }
        assertEquals(listOf(204, 403), requests.map { it.join() }.sorted())
    }

    @Test
    fun `verified email recovery works after password guessing exhausts the account limit`() {
        val browser = Browser()
        browser.session()
        val sent = stubResend()
        val email = "${UUID.randomUUID()}@example.test"
        val issued = browser.post("/api/auth/email/request", mapOf("email" to email))
        val id = json.readTree(issued.body()).path("challengeId").asString()
        assertEquals(204, browser.post("/api/auth/email/verify", mapOf("challengeId" to id, "code" to sent.get())).statusCode())
        browser.session()
        assertEquals(204, browser.put("/api/account/password", mapOf("password" to "initial password example")).statusCode())
        browser.session()
        val attacker = Browser()
        attacker.session()
        repeat(10) {
            assertEquals(401, attacker.post("/api/auth/password", mapOf("email" to email, "password" to "wrong")).statusCode())
        }
        assertEquals(429, attacker.post("/api/auth/password", mapOf("email" to email, "password" to "wrong")).statusCode())
        ageCode(email)
        val recoveryCode = browser.post("/api/auth/email/request", mapOf("email" to email, "linking" to true))
        assertEquals(200, recoveryCode.statusCode())
        val recoveryId = json.readTree(recoveryCode.body()).path("challengeId").asString()
        assertEquals(204, browser.post("/api/auth/email/verify",
            mapOf("challengeId" to recoveryId, "code" to sent.get(), "linking" to true)).statusCode())
        assertEquals(204, browser.put("/api/account/password", mapOf("password" to "a recovered password 123")).statusCode())
        assertEquals("email", browser.session().path("signInMethod").asString())
        assertTrue(browser.session().path("hasPassword").asBoolean())
    }

    @Test
    fun `member fallback labels do not expose verified email addresses`() {
        val subject = "privacy-${UUID.randomUUID()}"
        val email = "private-person-${UUID.randomUUID()}@example.test"
        val id = UUID.randomUUID()
        val hash = byteArrayOf(1, 2, 3)
        codes.issue(email, id, "privacy-browser", subject, hash)
        assertEquals(EmailVerification.Verified(subject), codes.verify(id, "privacy-browser", subject, hash))
        val club = clubs.create(subject, "Privacy club")
        val member = clubs.management(subject, club.id).members.single()
        assertEquals("Member " + member.id.toString().take(8), member.name)
        clubs.updateDisplayName(subject, "Chosen Member Name")
        assertEquals("Chosen Member Name", clubs.management(subject, club.id).members.single().name)
        clubs.savePreferredName(subject, "Preferred Member Name")
        assertEquals("Preferred Member Name", clubs.management(subject, club.id).members.single().name)
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
        val key = MessageDigest.getInstance("SHA-256").digest(email.toByteArray())
            .joinToString("") { "%02x".format(it) }
        DriverManager.getConnection(checkNotNull(System.getenv("TEAMSHEET_AUTH_TEST_JDBC")), testUsername(), testPassword()).use { c ->
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
        assertFailsWith<ResponseStatusException> { clubs.renamePersonal(subject, "  ") }
        assertFailsWith<ResponseStatusException> { clubs.renamePersonal(subject, "x".repeat(101)) }
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
        assertFailsWith<ResponseStatusException> { clubs.accept(organiser, unused) }
        assertFailsWith<ResponseStatusException> { clubs.preview(unused) }
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
        assertFailsWith<ResponseStatusException> { clubs.management(outsider, club.id) }
        assertFailsWith<ResponseStatusException> { clubs.rename(organiser, club.id, "Wrong") }
        assertFailsWith<ResponseStatusException> { clubs.revokeInvitations(organiser, club.id) }
        assertFailsWith<ResponseStatusException> { clubs.removeMember(organiser, club.id, member.id) }
        assertFailsWith<ResponseStatusException> { clubs.removeMember(owner, club.id, members.single { it.isYou }.id) }
        clubs.removeMember(owner, club.id, member.id)
        assertEquals(1, clubs.management(owner, club.id).members.size)
        assertFailsWith<ResponseStatusException> { clubs.withRosterAccess(organiser, club.id) { error("Removed member should not reach the roster") } }
        assertFailsWith<ResponseStatusException> { clubs.accept(organiser, invite) }
    }

    @Test
    fun `club membership shares a roster while personal rosters remain separate`() {
        val owner = "owner-${UUID.randomUUID()}"
        val organiser = "organiser-${UUID.randomUUID()}"
        val stranger = "stranger-${UUID.randomUUID()}"
        val club = clubs.create(owner, " Saturday Football ")
        assertEquals("Saturday Football", club.name)
        assertEquals("OWNER", club.role)
        assertFailsWith<ResponseStatusException> { clubs.rosterSubject(stranger, club.id) }
        val subject = clubs.rosterSubject(owner, club.id)
        val roster = SavedRoster(0, RosterDraft(players = listOf(Player("club-player", "Club player", 4))))
        assertTrue(rosters.save(subject, roster))
        val firstInvite = clubs.invite(owner, club.id)
        val replacement = clubs.invite(owner, club.id)
        assertFailsWith<ResponseStatusException> { clubs.accept(organiser, firstInvite) }
        assertEquals("ORGANISER", clubs.accept(organiser, replacement).role)
        assertEquals(clubs.rosterSubject(owner, club.id), clubs.rosterSubject(organiser, club.id))
        assertEquals(roster.draft, rosters.read(clubs.rosterSubject(organiser, club.id)).draft)
        assertEquals(RosterDraft(), rosters.read(organiser).draft)
        assertEquals(club.id, clubs.accept(organiser, replacement).id)
        assertFailsWith<ResponseStatusException> { clubs.accept(stranger, replacement) }
        assertFailsWith<ResponseStatusException> { clubs.invite(organiser, club.id) }
        assertEquals(listOf(club.id), clubs.list(organiser).map { it.id })
        assertEquals(emptyList(), clubs.list(stranger))
    }

    private fun stubResend(): AtomicReference<String> {
        val code = AtomicReference<String>()
        Mockito.`when`(resend.configured).thenReturn(true)
        Mockito.doAnswer {
            code.set(it.getArgument<String>(1))
            null
        }.`when`(resend).sendCode(anyString(), anyString(), anyBoolean(), anyBoolean())
        return code
    }

    private fun ageCode(email: String) {
        val url = checkNotNull(System.getenv("TEAMSHEET_AUTH_TEST_JDBC"))
        DriverManager.getConnection(url, testUsername(), testPassword()).use { connection ->
            connection.prepareStatement("UPDATE email_sign_in_codes SET issued_at = now() - interval '11 minutes' WHERE email = ?").use {
                it.setString(1, email)
                assertEquals(1, it.executeUpdate())
            }
        }
    }

    companion object {
        private val browserAddresses = AtomicInteger()
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
