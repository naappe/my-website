package com.phonedesk.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayAuthTest {
    @Test
    fun deviceIdMatchesCrossClientVector() {
        assertEquals("pd_vYVSrqAZQSyIHKdKfecnCA", RelayAuth.deviceIdFromPublicKey("PhoneDesk".toByteArray()))
    }

    @Test
    fun canonicalJsonSortsKeysAndMinifies() {
        assertEquals("{\"a\":1,\"z\":2}", RelayAuth.canonicalJson(mapOf("z" to 2, "a" to 1)).decodeToString())
    }

    @Test
    fun canonicalJsonEscapesStrings() {
        assertEquals(
            "{\"text\":\"line\\n\\\"quoted\\\"\"}",
            RelayAuth.canonicalJson(mapOf("text" to "line\n\"quoted\"")).decodeToString(),
        )
    }
}
