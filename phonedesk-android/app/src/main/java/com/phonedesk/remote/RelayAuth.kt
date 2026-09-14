package com.phonedesk.remote

import java.security.MessageDigest
import java.util.Base64

object RelayAuth {
    fun deviceIdFromPublicKey(publicKeyDer: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyDer).copyOfRange(0, 16)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return "pd_$token"
    }

    fun canonicalJson(data: Map<String, Any?>): ByteArray {
        val text = data.keys.sorted().joinToString(prefix = "{", postfix = "}", separator = ",") { key ->
            "\"${escape(key)}\":${encodeValue(data[key])}"
        }
        return text.toByteArray(Charsets.UTF_8)
    }

    fun canonicalRequest(
        method: String,
        path: String,
        timestamp: String,
        nonce: String,
        body: ByteArray,
    ): ByteArray {
        val bodyHash = MessageDigest.getInstance("SHA-256")
            .digest(body)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "${method.uppercase()}\n$path\n$timestamp\n$nonce\n$bodyHash"
            .toByteArray(Charsets.UTF_8)
    }

    private fun encodeValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"${escape(value)}\""
        is Boolean -> if (value) "true" else "false"
        is Byte, is Short, is Int, is Long -> value.toString()
        is Map<*, *> -> {
            val stringMap = value.entries.associate { entry ->
                require(entry.key is String) { "JSON object keys must be strings" }
                entry.key as String to entry.value
            }
            canonicalJson(stringMap).toString(Charsets.UTF_8)
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { encodeValue(it) }
        else -> throw IllegalArgumentException("Unsupported canonical JSON value: ${value::class.java.simpleName}")
    }

    private fun escape(value: String): String {
        val result = StringBuilder(value.length + 8)
        value.forEach { ch ->
            when (ch) {
                '\\' -> result.append("\\\\")
                '"' -> result.append("\\\"")
                '\n' -> result.append("\\n")
                '\r' -> result.append("\\r")
                '\t' -> result.append("\\t")
                '\b' -> result.append("\\b")
                '\u000C' -> result.append("\\f")
                else -> {
                    if (ch.code < 0x20) {
                        result.append("\\u%04x".format(ch.code))
                    } else {
                        result.append(ch)
                    }
                }
            }
        }
        return result.toString()
    }
}
