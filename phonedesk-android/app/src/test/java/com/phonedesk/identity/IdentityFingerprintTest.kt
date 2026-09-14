package com.phonedesk.identity

import org.junit.Assert.assertEquals
import org.junit.Test

class IdentityFingerprintTest {
    @Test
    fun `fingerprint uses first four sha256 bytes`() {
        assertEquals("BD:85:52:AE", IdentityFingerprint.shortSha256("PhoneDesk".toByteArray()))
    }

    @Test
    fun `same key has stable fingerprint`() {
        val key = byteArrayOf(1, 2, 3, 4, 5)
        assertEquals(IdentityFingerprint.shortSha256(key), IdentityFingerprint.shortSha256(key))
    }
}
