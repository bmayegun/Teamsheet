package com.biz.teamsheet.auth

import java.security.MessageDigest
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

internal class NetworkLimit(val waitSeconds: Int, val daily: Boolean = false) : RuntimeException()

@Repository
@Profile("roster")
class AuthenticationNetworkLimits(private val jdbc: JdbcClient) {
    @Transactional
    fun check(action: String, address: String) {
        when (action) {
            "password-login" -> {
                reserve(action, address, 60, 900)
                reserve(action, "shared-safety-ceiling", 10_000, 900)
            }
            "email-send" -> {
                reserve(action, address, 10, 900)
                reserve("email-send-daily", address, 50, 86400, daily = true)
            }
            "email-verify" -> reserve(action, address, 60, 900)
            "password-save" -> reserve(action, address, 20, 900)
            else -> error("Unknown authentication limit")
        }
        jdbc.sql("DELETE FROM authentication_network_limits WHERE expires_at < now() - interval '1 day'").update()
    }

    private fun reserve(action: String, address: String, limit: Int, seconds: Int, daily: Boolean = false) {
        val key = MessageDigest.getInstance("SHA-256").digest(address.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val bucket = "$action:$key"
        val updated = jdbc.sql("""
            INSERT INTO authentication_network_limits (bucket, expires_at) VALUES (?, now() + ? * interval '1 second')
            ON CONFLICT (bucket) DO UPDATE SET
                attempts = CASE WHEN authentication_network_limits.expires_at <= now()
                    THEN 1 ELSE authentication_network_limits.attempts + 1 END,
                expires_at = CASE WHEN authentication_network_limits.expires_at <= now()
                    THEN excluded.expires_at ELSE authentication_network_limits.expires_at END
            WHERE authentication_network_limits.attempts < ? OR authentication_network_limits.expires_at <= now()
        """).param(bucket).param(seconds).param(limit).update()
        if (updated == 0) {
            val wait = jdbc.sql("""
                SELECT greatest(1, ceil(extract(epoch FROM expires_at - now())))::int
                FROM authentication_network_limits WHERE bucket = ?
            """).param(bucket).query(Int::class.java).single()
            throw NetworkLimit(wait, daily)
        }
    }
}
