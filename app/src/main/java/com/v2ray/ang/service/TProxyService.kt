package com.v2ray.ang.service

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

class TProxyService(
    private val context: Context,
    private val vpnInterface: ParcelFileDescriptor,
    private val socksPort: Int = 10808
) {
    companion object {
        private const val TAG = "TProxyService"

        @JvmStatic
        private external fun TProxyStartService(configPath: String, fd: Int): Boolean

        @JvmStatic
        private external fun TProxyStopService(): Boolean

        @JvmStatic
        private external fun TProxyIsRunning(): Boolean

        @JvmStatic
        private external fun TProxyGetStats(): LongArray?

        init {
            try {
                System.loadLibrary("hev-socks5-tunnel")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load libhev-socks5-tunnel.so", e)
            }
        }
    }

    fun start(): Boolean {
        return try {
            val configContent = """
                tunnel:
                  mtu: 1360
                  ipv4: 172.19.0.1
                socks5:
                  port: $socksPort
                  address: 127.0.0.1
                  udp: 'udp'
                misc:
                  tcp-read-write-timeout: 300000
                  udp-read-write-timeout: 60000
                  log-level: warn
            """.trimIndent()

            val configFile = File(context.filesDir, "hev-socks5-tunnel.yaml").apply {
                writeText(configContent)
            }
            Log.i(TAG, "Starting hev-socks5-tunnel with fd: ${vpnInterface.fd}")
            TProxyStartService(configFile.absolutePath, vpnInterface.fd)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting hev-socks5-tunnel", e)
            false
        }
    }

    fun isRunning(): Boolean = try {
        TProxyIsRunning()
    } catch (e: Throwable) {
        false
    }

    fun stop() {
        try {
            TProxyStopService()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping hev-socks5-tunnel", e)
        }
    }
}
