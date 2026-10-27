package com.aether.masque.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.regex.Pattern

class AetherProcessService(private val context: Context) {
    companion object {
        private const val TAG = "AetherProcessService"
        const val SOCKS_PORT = 10808
        const val BINARY_NAME = "libaether.so"

        private val RTT_REGEX = Pattern.compile("([0-9.]+ms)")
        private val GW_REGEX = Pattern.compile("([0-9.]+:[0-9]+)")
    }

    private var process: Process? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 300)
    val logs: SharedFlow<String> = _logs

    var lastRtt: String = "-- ms"
        private set
    var lastGateway: String = "Cloudflare Edge"
        private set

    private fun ensureIdentityFile(): File {
        val identityFile = File(context.filesDir, "aether-masque.toml")
        if (!identityFile.exists() || identityFile.readText().contains("[REDACTED") || identityFile.length() < 300L) {
            identityFile.writeText(DefaultIdentity.MASQUE_TOML)
            Log.i(TAG, "Provisioned valid aether-masque.toml")
        }
        val baseConfig = File(context.filesDir, "aether.toml")
        if (!baseConfig.exists()) {
            baseConfig.writeText("")
        }
        return identityFile
    }

    fun start(onReady: () -> Unit, onError: (String) -> Unit) {
        scope.launch {
            try {
                val identityFile = ensureIdentityFile()

                val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
                if (!binary.exists()) {
                    val err = "Engine binary missing: ${binary.absolutePath}"
                    Log.e(TAG, err)
                    onError(err)
                    return@launch
                }

                if (!binary.canExecute()) {
                    binary.setExecutable(true, false)
                }

                val args = listOf(
                    binary.absolutePath,
                    "--protocol", "masque",
                    "--h2",
                    "--ip", "v4",
                    "--scan", "turbo",
                    "--quick-reconnect",
                    "--masque-config", identityFile.absolutePath,
                    "--bind", "127.0.0.1:$SOCKS_PORT"
                )

                Log.i(TAG, "Launching: ${args.joinToString(" ")}")
                _logs.emit("[CORE] Starting Cloudflare MASQUE HTTP/2 engine...")

                val builder = ProcessBuilder(args)
                    .directory(context.filesDir)
                    .redirectErrorStream(true)

                builder.environment().apply {
                    put("HOME", context.filesDir.absolutePath)
                    put("TMPDIR", context.cacheDir.absolutePath)
                    put("AETHER_CONFIG", File(context.filesDir, "aether.toml").absolutePath)
                    put("AETHER_MASQUE_CONFIG", identityFile.absolutePath)
                    put("AETHER_PROTOCOL", "masque")
                }

                val proc = builder.start()
                process = proc

                var notifiedReady = false

                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                var line: String? = null
                while (coroutineContext.isActive && proc.isAlive && reader.readLine().also { line = it } != null) {
                    val text = line ?: continue
                    Log.d(TAG, text)
                    _logs.emit(text)

                    // Extract RTT
                    val rttMatcher = RTT_REGEX.matcher(text)
                    if (rttMatcher.find()) {
                        lastRtt = rttMatcher.group(1) ?: lastRtt
                    }

                    // Extract Gateway
                    val gwMatcher = GW_REGEX.matcher(text)
                    if (gwMatcher.find()) {
                        lastGateway = gwMatcher.group(1) ?: lastGateway
                    }

                    // Trigger ready state when socks listener or tunnel is established
                    if (!notifiedReady && (text.contains("listening") || text.contains("127.0.0.1:$SOCKS_PORT") || text.contains("tunnel validated") || text.contains("warp on") || text.contains("connected"))) {
                        notifiedReady = true
                        withContext(Dispatchers.Main) {
                            onReady()
                        }
                    }
                }

                val exitCode = proc.waitFor()
                Log.w(TAG, "Aether process terminated with code $exitCode")
                _logs.emit("[CORE] Engine stopped (code $exitCode)")
            } catch (e: Exception) {
                Log.e(TAG, "Process error", e)
                _logs.emit("[ERR] ${e.localizedMessage}")
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Launch failed")
                }
            }
        }
    }

    fun stop() {
        try {
            process?.let {
                it.destroy()
                Log.i(TAG, "Process destroyed")
            }
            process = null
            scope.coroutineContext.cancelChildren()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping process", e)
        }
    }
}
