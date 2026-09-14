package com.phonedesk

interface AdbClient {
    suspend fun pair(input: PairingInput)
    suspend fun verify(input: ConnectionInput): Boolean
}

data class TrustedProfile(
    val host: String,
    val port: Int,
) {
    val address: String
        get() = "$host:$port"
}

interface ProfileStore {
    fun save(profile: TrustedProfile)
    fun load(): TrustedProfile?
    fun clear()
}

class PhoneDeskCoordinator(
    private val client: AdbClient,
    private val store: ProfileStore,
) {
    suspend fun pair(input: PairingInput): Result<Unit> =
        runCatching { client.pair(input) }

    suspend fun verifyAndSave(input: ConnectionInput): Result<TrustedProfile> =
        runCatching {
            check(client.verify(input)) { "ADB verification failed" }
            TrustedProfile(input.host, input.port).also(store::save)
        }

    fun savedProfile(): TrustedProfile? = store.load()

    fun clearProfile() = store.clear()
}
