package com.phonedesk.remote

import android.content.Context


data class TrustedComputer(
    val deviceId: String,
    val displayName: String,
    val fingerprint: String,
    val publicKeyDerBase64: String,
)

interface TrustedComputerStore {
    fun load(): TrustedComputer?
    fun save(computer: TrustedComputer)
    fun clear()
}

class SharedPreferencesTrustedComputerStore(context: Context) : TrustedComputerStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun load(): TrustedComputer? {
        val deviceId = preferences.getString(KEY_DEVICE_ID, null) ?: return null
        val displayName = preferences.getString(KEY_DISPLAY_NAME, null) ?: return null
        val fingerprint = preferences.getString(KEY_FINGERPRINT, null) ?: return null
        val publicKey = preferences.getString(KEY_PUBLIC_KEY, null) ?: return null
        return TrustedComputer(deviceId, displayName, fingerprint, publicKey)
    }

    override fun save(computer: TrustedComputer) {
        preferences.edit()
            .putString(KEY_DEVICE_ID, computer.deviceId)
            .putString(KEY_DISPLAY_NAME, computer.displayName)
            .putString(KEY_FINGERPRINT, computer.fingerprint)
            .putString(KEY_PUBLIC_KEY, computer.publicKeyDerBase64)
            .apply()
    }

    override fun clear() {
        preferences.edit().clear().apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "phonedesk_remote_trust"
        private const val KEY_DEVICE_ID = "trusted_computer_device_id"
        private const val KEY_DISPLAY_NAME = "trusted_computer_display_name"
        private const val KEY_FINGERPRINT = "trusted_computer_fingerprint"
        private const val KEY_PUBLIC_KEY = "trusted_computer_public_key_der_b64"
    }
}
