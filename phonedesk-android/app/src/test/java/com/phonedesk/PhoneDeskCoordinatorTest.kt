package com.phonedesk

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneDeskCoordinatorTest {
    @Test
    fun pairingDoesNotPersistPairingCodeOrProfile() = runBlocking {
        val client = FakeAdbClient(pairSucceeds = true, verifySucceeds = true)
        val store = FakeProfileStore()
        val coordinator = PhoneDeskCoordinator(client, store)

        val input = PairingInput("192.168.8.15", 45021, "117449")
        val result = coordinator.pair(input)

        assertTrue(result.isSuccess)
        assertEquals(input, client.lastPairInput)
        assertNull(store.saved)
    }

    @Test
    fun verifiedConnectionIsSavedAsTrustedProfile() = runBlocking {
        val client = FakeAdbClient(pairSucceeds = true, verifySucceeds = true)
        val store = FakeProfileStore()
        val coordinator = PhoneDeskCoordinator(client, store)

        val input = ConnectionInput("192.168.8.15", 39117)
        val result = coordinator.verifyAndSave(input)

        assertTrue(result.isSuccess)
        assertEquals(TrustedProfile("192.168.8.15", 39117), store.saved)
    }

    @Test
    fun failedVerificationIsNotSaved() = runBlocking {
        val client = FakeAdbClient(pairSucceeds = true, verifySucceeds = false)
        val store = FakeProfileStore()
        val coordinator = PhoneDeskCoordinator(client, store)

        val result = coordinator.verifyAndSave(ConnectionInput("192.168.8.15", 39117))

        assertTrue(result.isFailure)
        assertNull(store.saved)
    }

    private class FakeAdbClient(
        private val pairSucceeds: Boolean,
        private val verifySucceeds: Boolean,
    ) : AdbClient {
        var lastPairInput: PairingInput? = null

        override suspend fun pair(input: PairingInput) {
            lastPairInput = input
            if (!pairSucceeds) error("pair failed")
        }

        override suspend fun verify(input: ConnectionInput): Boolean = verifySucceeds
    }

    private class FakeProfileStore : ProfileStore {
        var saved: TrustedProfile? = null

        override fun save(profile: TrustedProfile) {
            saved = profile
        }

        override fun load(): TrustedProfile? = saved

        override fun clear() {
            saved = null
        }
    }
}
