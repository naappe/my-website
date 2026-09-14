package com.phonedesk

data class ConnectionInput(
    val host: String,
    val port: Int,
) {
    companion object {
        fun parse(hostText: String, portText: String): Result<ConnectionInput> =
            runCatching {
                val host = hostText.trim()
                require(host.isNotEmpty()) { "Enter phone IP address" }

                val port = portText.trim().toInt()
                require(port in 1..65535) { "Port must be between 1 and 65535" }

                ConnectionInput(host, port)
            }
    }
}
