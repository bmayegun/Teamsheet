package com.biz.teamsheet

import com.biz.teamsheet.auth.SignInEmail
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper

class SignInEmailTests {
    @Test
    fun `local delivery captures the sign-in email without a Resend credential`() {
        val json = JsonMapper.builder().build()
        val captured = AtomicReference<String>()
        val auth = AtomicReference<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/send") { request ->
            captured.set(request.requestBody.bufferedReader().readText())
            auth.set(request.requestHeaders.getFirst("Authorization"))
            request.sendResponseHeaders(200, -1)
            request.close()
        }
        server.start()
        try {
            val mailer = SignInEmail("", "", json, "http://127.0.0.1:${server.address.port}/")
            assertTrue(mailer.configured)
            mailer.sendCode("organiser@example.test", "012345", false)
            val message = json.readTree(captured.get())
            assertEquals("organiser@example.test", message.path("To").path(0).path("Email").asText())
            assertEquals("signin@teamsheet.test", message.path("From").path("Email").asText())
            assertTrue(message.path("Text").asText().contains("sign in to TeamSheet: 012345"))
            assertEquals(null, auth.get())
        } finally {
            server.stop(0)
        }
    }
}
