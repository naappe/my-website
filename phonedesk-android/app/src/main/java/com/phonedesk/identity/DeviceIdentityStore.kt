package com.phonedesk.identity

data class DeviceIdentity(
    val publicKeyDer: ByteArray,
    val fingerprint: String,
)

interface DeviceIdentityStore {
    fun getOrCreate(): DeviceIdentity
    fun sign(message: ByteArray): ByteArray
}
