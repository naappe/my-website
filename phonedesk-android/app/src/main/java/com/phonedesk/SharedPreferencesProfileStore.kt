package com.phonedesk

import android.content.Context

class SharedPreferencesProfileStore(context: Context) : ProfileStore {
    private val prefs = context.getSharedPreferences("phonedesk_profile", Context.MODE_PRIVATE)

    override fun save(profile: TrustedProfile) {
        prefs.edit()
            .putString(KEY_HOST, profile.host)
            .putInt(KEY_PORT, profile.port)
            .apply()
    }

    override fun load(): TrustedProfile? {
        val host = prefs.getString(KEY_HOST, null)?.trim().orEmpty()
        val port = prefs.getInt(KEY_PORT, -1)
        return if (host.isNotEmpty() && port in 1..65535) {
            TrustedProfile(host, port)
        } else {
            null
        }
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_HOST = "trusted_host"
        const val KEY_PORT = "trusted_port"
    }
}
