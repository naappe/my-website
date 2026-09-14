package com.phonedesk.remote

import android.os.Build
import com.phonedesk.identity.DeviceIdentityStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.UUID

class RelayApi(
    private val baseUrl: String,
    private val identityStore: DeviceIdentityStore,
    private val displayName: String = Build.MODEL.ifBlank { "Android phone" },
) : PairingRelayApi {
    private var deviceId: String? = null

    override suspend fun registerDevice(): DeviceRegistration {
        val identity = identityStore.getOrCreate()
        val body = RelayAuth.canonicalJson(
            mapOf(
                "display_name" to displayName,
                "platform" to "android",
                "public_key_der_b64" to Base64.getEncoder().encodeToString(identity.publicKeyDer),
            ),
        )
        val response = execute("POST", "/v1/devices/register", body, signed = false)
        val json = JSONObject(response)
        return DeviceRegistration(
            deviceId = json.getString("device_id"),
            fingerprint = json.getString("fingerprint"),
        ).also { registration -> deviceId = registration.deviceId }
    }

    override suspend fun createPairing(): PairingOffer {
        ensureRegistered()
        val response = execute("POST", "/v1/pairings", RelayAuth.canonicalJson(emptyMap()), signed = true)
        val json = JSONObject(response)
        return PairingOffer(
            pairingId = json.getString("pairing_id"),
            manualCode = json.getString("manual_code"),
            expiresInSeconds = json.getInt("expires_in_seconds"),
        )
    }

    override suspend fun getPairingStatus(pairingId: String): PairingCandidate {
        ensureRegistered()
        val path = "/v1/pairings/$pairingId"
        val json = JSONObject(execute("GET", path, ByteArray(0), signed = true))
        if (json.isNull("computer")) {
            throw IllegalStateException("No computer has claimed this pairing yet")
        }
        val computer = json.getJSONObject("computer")
        return PairingCandidate(
            deviceId = computer.getString("device_id"),
            displayName = computer.getString("display_name"),
            fingerprint = computer.getString("fingerprint"),
            publicKeyDerBase64 = computer.getString("public_key_der_b64"),
        )
    }

    override suspend fun confirmPairing(pairingId: String, approved: Boolean) {
        ensureRegistered()
        execute(
            "POST",
            "/v1/pairings/$pairingId/confirm",
            RelayAuth.canonicalJson(mapOf("approved" to approved)),
            signed = true,
        )
    }

    private suspend fun ensureRegistered(): String {
        if (deviceId == null) registerDevice()
        return requireNotNull(deviceId)
    }

    private suspend fun execute(
        method: String,
        path: String,
        body: ByteArray,
        signed: Boolean,
    ): String = withContext(Dispatchers.IO) {
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")

            if (signed) {
                val currentDeviceId = requireNotNull(deviceId) { "PhoneDesk device is not registered" }
                val timestamp = (System.currentTimeMillis() / 1000L).toString()
                val nonce = UUID.randomUUID().toString().replace("-", "")
                val canonical = RelayAuth.canonicalRequest(method, path, timestamp, nonce, body)
                val signature = identityStore.sign(canonical)
                connection.setRequestProperty("X-PhoneDesk-Device", currentDeviceId)
                connection.setRequestProperty("X-PhoneDesk-Timestamp", timestamp)
                connection.setRequestProperty("X-PhoneDesk-Nonce", nonce)
                connection.setRequestProperty(
                    "X-PhoneDesk-Signature",
                    Base64.getEncoder().encodeToString(signature),
                )
            }

            if (method == "POST") {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { stream -> stream.write(body) }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("Relay request failed ($status): ${response.take(180)}")
            }
            response
        } finally {
            connection.disconnect()
        }
    }
}
