package com.biz.teamsheet

import com.biz.teamsheet.auth.PasswordSafety
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class PasswordSafetyTests {
    private val safety = PasswordSafety()
    private val suffix = "A".repeat(35)

    @Test
    fun `common passwords are rejected before any network lookup`() {
        val error = assertFailsWith<ResponseStatusException> { safety.check("Football1!") }
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.statusCode)
    }

    @Test
    fun `breach counts are matched exactly and padding is ignored`() {
        assertTrue(safety.appearsInRange(suffix, "$suffix:42\r\n"))
        assertFalse(safety.appearsInRange(suffix, "$suffix:0\r\n"))
        assertFalse(safety.appearsInRange(suffix, "${"B".repeat(35)}:42\r\n"))
        assertFailsWith<IOException> { safety.appearsInRange(suffix, "unavailable") }
        assertFailsWith<IOException> { safety.appearsInRange(suffix, "") }
    }
}
