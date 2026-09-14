package com.phonedesk

data class PairingInput(
    val host: String,
    val port: Int,
    val code: String,
) {
    companion object {
        fun parse(hostText: String, portText: String, codeText: String): Result<PairingInput> =
            runCatching {
                val host = hostText.trim()
                require(host.isNotEmpty()) { "Enter phone IP address" }

                val port = portText.trim().toInt()
                require(port in 1..65535) { "Port must be between 1 and 65535" }

                val code = codeText.trim()
                require(code.matches(Regex("\\d{6}"))) { "Pairing code must be 6 digits" }

                PairingInput(host, port, code)
            }
    }
}
