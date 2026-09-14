package com.phonedesk.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class AndroidDeviceIdentityStore(
    private val alias: String = DEFAULT_ALIAS,
) : DeviceIdentityStore {
    private val lock = Any()

    override fun getOrCreate(): DeviceIdentity = synchronized(lock) {
        val keyStore = loadKeyStore()
        ensureKeyExists(keyStore)

        val certificate = checkNotNull(keyStore.getCertificate(alias)) {
            "PhoneDesk identity certificate is unavailable"
        }
        val publicKeyDer = certificate.publicKey.encoded.copyOf()
        DeviceIdentity(
            publicKeyDer = publicKeyDer,
            fingerprint = IdentityFingerprint.shortSha256(publicKeyDer),
        )
    }

    override fun sign(message: ByteArray): ByteArray = synchronized(lock) {
        val keyStore = loadKeyStore()
        ensureKeyExists(keyStore)

        val privateKey = keyStore.getKey(alias, null) as? PrivateKey
            ?: error("PhoneDesk identity private key is unavailable")

        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(message)
            sign()
        }
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private fun ensureKeyExists(keyStore: KeyStore) {
        if (keyStore.containsAlias(alias)) return

        KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEY_STORE,
        ).apply {
            initialize(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
            generateKeyPair()
        }
    }

    companion object {
        const val DEFAULT_ALIAS = "phonedesk_remote_identity_v1"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}
