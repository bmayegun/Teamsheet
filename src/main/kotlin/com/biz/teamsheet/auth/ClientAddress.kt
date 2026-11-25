package com.biz.teamsheet.auth

import jakarta.servlet.http.HttpServletRequest
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Collections
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.security.web.util.matcher.IpAddressMatcher
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

@Component
@Profile("roster")
class ClientAddress(@Value("\${teamsheet.auth.trusted-proxies:}") ranges: String) {
    private val trusted = ranges.split(',').map(String::trim).filter(String::isNotEmpty).map {
        require(it.contains('/') && !it.endsWith("/0")) { "Specify explicit trusted proxy CIDRs, not a catch-all range." }
        require(numericAddress(it.substringBefore('/')) != null) { "Trusted proxies must use numeric CIDRs." }
        IpAddressMatcher(it)
    }

    fun resolve(request: HttpServletRequest): String {
        val peer = numericAddress(request.remoteAddr) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        if (trusted.none { it.matches(peer) }) return peer
        val headers = Collections.list(request.getHeaders("X-Forwarded-For"))
        if (headers.isEmpty()) return peer
        if (headers.sumOf(String::length) > 4096) throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        val chain = headers.flatMap { it.split(',') }
        if (chain.size > 20) throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        var current = peer
        for (address in chain.asReversed()) {
            if (trusted.none { it.matches(current) }) break
            current = numericAddress(address.trim()) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        }
        return current
    }

    private fun numericAddress(address: String): String? {
        val ipv4 = address.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) &&
            address.split('.').all { it.toInt() in 0..255 }
        val ipv6 = ':' in address && address.matches(Regex("[0-9a-fA-F:.]+"))
        if (!ipv4 && !ipv6) return null
        // Only numeric addresses reach InetAddress, so this never performs a DNS lookup.
        return try { InetAddress.getByName(address).hostAddress } catch (_: UnknownHostException) { null }
    }
}
