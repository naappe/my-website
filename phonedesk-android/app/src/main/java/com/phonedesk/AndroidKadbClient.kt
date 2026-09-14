package com.phonedesk

import android.content.Context
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath

class AndroidKadbClient(context: Context) : AdbClient {
    init {
        val keyFile = context.filesDir.resolve("phonedesk_adb_private_key.pem")
        KadbCert.configure(
            store = OkioFilePrivateKeyStore(keyFile.absolutePath.toPath())
        )
        KadbCert.ensureReady()
    }

    override suspend fun pair(input: PairingInput) {
        Kadb.pair(
            host = input.host,
            port = input.port,
            pairingCode = input.code,
            name = "PhoneDesk"
        )
    }

    override suspend fun verify(input: ConnectionInput): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                Kadb.create(
                    host = input.host,
                    port = input.port,
                    connectTimeout = 10_000,
                    socketTimeout = 10_000,
                ).use { kadb ->
                    val response = kadb.shell("echo phonedesk")
                    response.exitCode == 0 && response.output.trim() == "phonedesk"
                }
            }.getOrDefault(false)
        }
}
