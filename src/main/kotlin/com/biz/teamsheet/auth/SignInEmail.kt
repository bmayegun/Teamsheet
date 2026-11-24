package com.biz.teamsheet.auth

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

@Component
@Profile("roster")
class SignInEmail(
    @Value("\${teamsheet.email.resend-api-key}") private val apiKey: String,
    @Value("\${teamsheet.email.sender-email}") private val sender: String,
    private val json: JsonMapper,
    @Value("\${teamsheet.email.mailpit-url:}") private val mailpitUrl: String = "",
) {
    val configured: Boolean get() = mailpitUrl.isNotBlank() || (apiKey.isNotBlank() && sender.isNotBlank())
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @Throws(IOException::class, InterruptedException::class)
    fun sendCode(email: String, code: String, linking: Boolean) {
        val action = if (linking) "link email sign-in to your TeamSheet account" else "sign in to TeamSheet"
        val text = "Use this code to $action: $code\n\n" +
            "It expires in 10 minutes. If you did not request it, you can ignore this email."
        val message = if (mailpitUrl.isNotBlank()) mapOf(
            "From" to mapOf("Name" to "TeamSheet", "Email" to "signin@teamsheet.test"),
            "To" to listOf(mapOf("Email" to email)),
            "Subject" to "Your TeamSheet verification code",
            "Text" to text,
        ) else mapOf(
            "from" to "TeamSheet <$sender>",
            "to" to listOf(email),
            "subject" to "Your TeamSheet verification code",
            "text" to text,
        )
        val endpoint = if (mailpitUrl.isNotBlank()) "${mailpitUrl.trimEnd('/')}/api/v1/send"
            else "https://api.resend.com/emails"
        val request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(10))
            .apply { if (mailpitUrl.isBlank()) header("Authorization", "Bearer $apiKey") }
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(message)))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() != 200) throw IOException("Email delivery returned HTTP ${response.statusCode()}.")
    }
}
