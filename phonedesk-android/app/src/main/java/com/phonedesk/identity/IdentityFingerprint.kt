package com.phonedesk.identity

import java.security.MessageDigest

object IdentityFingerprint {
    fun shortSha256(publicKeyDer: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(publicKeyDer)
            .take(4)
            .joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
}
