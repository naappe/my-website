package com.phonedesk.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityStoreTest {
    private class FakeStore : DeviceIdentityStore {
        private val publicKey = "public-key".toByteArray()

        override fun getOrCreate() = DeviceIdentity(
            publicKeyDer = publicKey.copyOf(),
            fingerprint = IdentityFingerprint.shortSha256(publicKey),
        )

        override fun sign(message: ByteArray): ByteArray =
            ("sig:" + message.decodeToString()).toByteArray()
    }

    @Test
    fun `store exposes stable public identity and signing capability`() {
        val store = FakeStore()
        val first = store.getOrCreate()
        val second = store.getOrCreate()

        assertTrue(first.publicKeyDer.contentEquals(second.publicKeyDer))
        assertEquals(first.fingerprint, second.fingerprint)
        assertTrue(store.sign("hello".toByteArray()).isNotEmpty())
    }
}
