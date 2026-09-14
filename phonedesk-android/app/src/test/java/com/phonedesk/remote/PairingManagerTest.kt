package com.phonedesk.remote

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class FakeRelayApi : PairingRelayApi {
    var confirmed: Boolean? = null

    override suspend fun registerDevice() = DeviceRegistration("pd_phone", "AA:AA:AA:AA")
    override suspend fun createPairing() = PairingOffer("p1", "123456", 300)
    override suspend fun getPairingStatus(pairingId: String) =
        PairingCandidate("pd_pc", "Office Laptop", "BB:BB:BB:BB", "BASE64PUBLIC")

    override suspend fun confirmPairing(pairingId: String, approved: Boolean) {
        confirmed = approved
    }
}

private class FakeTrustedStore : TrustedComputerStore {
    private var value: TrustedComputer? = null
    override fun load() = value
    override fun save(computer: TrustedComputer) { value = computer }
    override fun clear() { value = null }
}

class PairingManagerTest {
    @Test
    fun storesComputerOnlyAfterExplicitApproval() = runTest {
        val relay = FakeRelayApi()
        val store = FakeTrustedStore()
        val manager = PairingManager(relay, store)

        val offer = manager.begin()
        assertEquals("123456", offer.manualCode)
        val candidate = manager.refresh()
        assertEquals("Office Laptop", candidate.displayName)
        assertNull(store.load())

        manager.confirm(true)

        assertEquals("Office Laptop", store.load()?.displayName)
        assertEquals(true, relay.confirmed)
    }

    @Test
    fun denialNeverPersistsComputer() = runTest {
        val relay = FakeRelayApi()
        val store = FakeTrustedStore()
        val manager = PairingManager(relay, store)
        manager.begin()
        manager.refresh()

        manager.confirm(false)

        assertNull(store.load())
        assertEquals(false, relay.confirmed)
    }
}
