package com.phonedesk.remote


data class DeviceRegistration(
    val deviceId: String,
    val fingerprint: String,
)

data class PairingOffer(
    val pairingId: String,
    val manualCode: String,
    val expiresInSeconds: Int,
)

data class PairingCandidate(
    val deviceId: String,
    val displayName: String,
    val fingerprint: String,
    val publicKeyDerBase64: String,
)

interface PairingRelayApi {
    suspend fun registerDevice(): DeviceRegistration
    suspend fun createPairing(): PairingOffer
    suspend fun getPairingStatus(pairingId: String): PairingCandidate
    suspend fun confirmPairing(pairingId: String, approved: Boolean)
}

class PairingManager(
    private val relay: PairingRelayApi,
    private val store: TrustedComputerStore,
) {
    private var offer: PairingOffer? = null
    private var candidate: PairingCandidate? = null

    suspend fun begin(): PairingOffer {
        relay.registerDevice()
        return relay.createPairing().also { created ->
            offer = created
            candidate = null
        }
    }

    suspend fun refresh(): PairingCandidate {
        val current = requireNotNull(offer) { "Pairing has not been started" }
        return relay.getPairingStatus(current.pairingId).also { found ->
            candidate = found
        }
    }

    suspend fun confirm(approved: Boolean) {
        val currentOffer = requireNotNull(offer) { "Pairing has not been started" }
        val currentCandidate = requireNotNull(candidate) { "No computer has claimed this pairing yet" }

        relay.confirmPairing(currentOffer.pairingId, approved)
        if (approved) {
            store.save(
                TrustedComputer(
                    deviceId = currentCandidate.deviceId,
                    displayName = currentCandidate.displayName,
                    fingerprint = currentCandidate.fingerprint,
                    publicKeyDerBase64 = currentCandidate.publicKeyDerBase64,
                ),
            )
        }
        offer = null
        candidate = null
    }

    fun trustedComputer(): TrustedComputer? = store.load()

    fun clearTrustedComputer() = store.clear()
}
