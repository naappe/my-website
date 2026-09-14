package com.phonedesk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingInputTest {
    @Test
    fun validPairingInputParses() {
        val result = PairingInput.parse("192.168.8.15", "45021", "117449")
        assertTrue(result.isSuccess)
        val input = result.getOrThrow()
        assertEquals("192.168.8.15", input.host)
        assertEquals(45021, input.port)
        assertEquals("117449", input.code)
    }

    @Test
    fun pairingCodeMustBeSixDigits() {
        val result = PairingInput.parse("192.168.8.15", "45021", "12345")
        assertTrue(result.isFailure)
    }

    @Test
    fun portMustBeInTcpRange() {
        assertTrue(PairingInput.parse("192.168.8.15", "0", "117449").isFailure)
        assertTrue(PairingInput.parse("192.168.8.15", "70000", "117449").isFailure)
    }

    @Test
    fun hostCannotBeBlank() {
        assertTrue(PairingInput.parse(" ", "45021", "117449").isFailure)
    }
}
