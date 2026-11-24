package com.biz.teamsheet.auth

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.Locale
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
@Profile("roster")
class PasswordSafety {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val common = setOf(
        "password", "password1", "password123", "password123!", "12345678", "123456789",
        "1234567890", "qwerty123", "qwertyuiop", "football", "football1", "football1!",
        "letmein123", "welcome1", "teamsheet", "teamsheet1", "teamsheet123",
    )

    fun check(password: String) {
        if (password.lowercase(Locale.ROOT) in common) {
            throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Choose a less common password.")
        }
        val hash = MessageDigest.getInstance("SHA-1").digest(password.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }
        val request = HttpRequest.newBuilder(URI.create("https://api.pwnedpasswords.com/range/${hash.take(5)}"))
            .timeout(Duration.ofSeconds(5))
            .header("Add-Padding", "true")
            .GET().build()
        try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) throw IOException("Password screening unavailable")
            if (appearsInRange(hash.drop(5), response.body())) {
                throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Choose a password that has not appeared in a data breach.")
            }
        } catch (_: IOException) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)
        }
    }

    internal fun appearsInRange(suffix: String, body: String): Boolean {
        val entries = body.lineSequence().filter { it.isNotBlank() }.toList()
        if (entries.isEmpty() || entries.any { !it.matches(Regex("[A-Fa-f0-9]{35}:[0-9]+")) }) {
            throw IOException("Invalid password screening response")
        }
        return entries.any {
            val (candidate, count) = it.split(':')
            candidate.equals(suffix, ignoreCase = true) && count.toLongOrNull()?.let { total -> total > 0 } == true
        }
    }
}
