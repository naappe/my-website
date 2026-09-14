package com.phonedesk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionInputTest {
    @Test
    fun validConnectionInputParses() {
        val result = ConnectionInput.parse("192.168.8.15", "39117")
        assertTrue(result.isSuccess)
        val input = result.getOrThrow()
        assertEquals("192.168.8.15", input.host)
        assertEquals(39117, input.port)
    }

    @Test
    fun connectionHostCannotBeBlank() {
        assertTrue(ConnectionInput.parse(" ", "39117").isFailure)
    }

    @Test
    fun connectionPortMustBeValid() {
        assertTrue(ConnectionInput.parse("192.168.8.15", "0").isFailure)
        assertTrue(ConnectionInput.parse("192.168.8.15", "70000").isFailure)
        assertTrue(ConnectionInput.parse("192.168.8.15", "abc").isFailure)
    }
}
