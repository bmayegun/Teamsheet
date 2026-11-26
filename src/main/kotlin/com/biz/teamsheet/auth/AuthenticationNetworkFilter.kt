package com.biz.teamsheet.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.server.ResponseStatusException

class AuthenticationNetworkFilter(
    private val addresses: ClientAddress,
    private val limits: AuthenticationNetworkLimits,
) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val action = when (request.method to request.servletPath) {
            "POST" to "/api/auth/password" -> "password-login"
            "POST" to "/api/auth/email/request" -> "email-send"
            "POST" to "/api/auth/email/verify" -> "email-verify"
            "PUT" to "/api/account/password" -> "password-save"
            else -> null
        }
        if (action != null) {
            try {
                limits.check(action, addresses.resolve(request))
            } catch (error: ResponseStatusException) {
                response.status = error.statusCode.value()
                response.contentType = "application/json"
                response.setHeader("Cache-Control", "no-store")
                response.writer.write("""{"code":"invalid_client_address"}""")
                return
            } catch (error: NetworkLimit) {
                response.status = 429
                response.contentType = "application/json"
                response.setHeader("Cache-Control", "no-store")
                response.setHeader("Retry-After", error.waitSeconds.toString())
                val code = if (error.daily) "network_daily" else "network"
                response.writer.write("""{"limit":"$code","code":"authentication_network_limit"}""")
                return
            }
        }
        chain.doFilter(request, response)
    }
}
