package com.biz.teamsheet

import com.biz.teamsheet.auth.ClientAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.server.ResponseStatusException

class ClientAddressTests {
    private fun request(peer: String, vararg headers: String) = MockHttpServletRequest().apply {
        remoteAddr = peer
        headers.forEach { addHeader("X-Forwarded-For", it) }
        addHeader("CF-Connecting-IP", "192.0.2.99")
        addHeader("X-Real-IP", "192.0.2.99")
    }

    @Test
    fun `direct clients cannot forge their address with headers`() {
        assertEquals("198.51.100.4", ClientAddress("").resolve(request("198.51.100.4", "192.0.2.1")))
        assertEquals("198.51.100.4", ClientAddress("10.0.0.0/24").resolve(request("198.51.100.4", "192.0.2.1")))
    }

    @Test
    fun `trusted proxy chain stops at the first untrusted address from the right`() {
        val resolver = ClientAddress("10.0.0.0/24")
        assertEquals("198.51.100.4", resolver.resolve(request("10.0.0.2", "192.0.2.1,198.51.100.4,10.0.0.1")))
        assertEquals("198.51.100.4", resolver.resolve(request("10.0.0.2", "192.0.2.1", "198.51.100.4,10.0.0.1")))
        assertEquals("10.0.0.2", resolver.resolve(request("10.0.0.2")))
    }

    @Test
    fun `invalid proxy addresses do not trigger hostname lookups`() {
        val resolver = ClientAddress("10.0.0.0/24")
        for (header in listOf("deadbeef", "example.com", "999.0.0.1", "")) {
            assertFailsWith<ResponseStatusException> { resolver.resolve(request("10.0.0.2", header)) }
        }
        assertFailsWith<IllegalArgumentException> { ClientAddress("0.0.0.0/0") }
    }

    @Test
    fun `IPv6 addresses use a canonical representation`() {
        val resolver = ClientAddress("")
        assertEquals(resolver.resolve(request("2001:db8::1")), resolver.resolve(request("2001:0db8:0:0:0:0:0:1")))
    }
}
