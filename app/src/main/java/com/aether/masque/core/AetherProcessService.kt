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

class AetherProcessService(
    private val context: Context,
    private val targetPeer: String? = null,
    private val forceFreshScan: Boolean = false,
    private val enableMim: Boolean = false
) {
    companion object {
        private const val TAG = "AetherProcessService"
        const val SOCKS_PORT = 10808
        const val BINARY_NAME = "libaether.so"

        private val RTT_REGEX = Pattern.compile("([0-9.]+ms)")
        private val GW_REGEX = Pattern.compile("([0-9.]+:[0-9]+)")
    }

    private var process: Process? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 500)
    val logs: SharedFlow<String> = _logs

    var lastRtt: String = "-- ms"
        private set
    var lastGateway: String = targetPeer ?: "Cloudflare Edge"
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

                // Dynamic hardware-aware profiling:
                // Prevents CPU thrashing and out-of-order stalls on budget devices (e.g. Helio P22 / A10s)
                // while maintaining ultra-fast socket queues on mid-range and flagship processors.
                val maxMemMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
                val cores = Runtime.getRuntime().availableProcessors()
                val perfProfile = when {
                    cores <= 4 || maxMemMb < 256 -> "low"
                    else -> "medium" // "medium" is optimal for mobile Android devices to avoid excessive server thread pool spawning
                }

                val args = mutableListOf(
                    binary.absolutePath,
                    "--h2",
                    "--ip", "v4",
                    "--perf", perfProfile,
                    "--no-data-check",
                    "--masque-config", identityFile.absolutePath,
                    "--bind", "127.0.0.1:$SOCKS_PORT"
                )

                if (enableMim) {
                    args.add("--mim")
                    if (!targetPeer.isNullOrBlank() && targetPeer != "auto") {
                        args.add("--mim-outer")
                        args.add(targetPeer)
                        _logs.emit("[CORE] MIM Dual-Hop: Outer Edge $targetPeer, Exit IP -> Europe...")
                    } else {
                        args.add("--mim-scan")
                        _logs.emit("[CORE] MIM Dual-Hop: Dynamic Mesh, Exit IP -> Europe...")
                    }
                } else {
                    args.add("--protocol")
                    args.add("masque")

                    if (!targetPeer.isNullOrBlank() && targetPeer != "auto") {
                        args.add("--peer")
                        args.add(targetPeer)
                        _logs.emit("[CORE] Direct Peering Mode: locking to $targetPeer...")
                    } else if (forceFreshScan) {
                        args.add("--scan")
                        args.add("balanced")
                        args.add("--no-quick-reconnect")
                        _logs.emit("[CORE] Hunt Mode: sweeping Anycast pool for lowest latency edge...")
                    } else {
                        args.add("--scan")
                        args.add("turbo")
                        args.add("--quick-reconnect")
                        _logs.emit("[CORE] Turbo Anycast Mesh Mode (Profile: $perfProfile)...")
                    }
                }

                Log.i(TAG, "Launching: ${args.joinToString(" ")}")

                val builder = ProcessBuilder(args)
                    .directory(context.filesDir)
                    .redirectErrorStream(true)

                builder.environment().apply {
                    put("HOME", context.filesDir.absolutePath)
                    put("TMPDIR", context.cacheDir.absolutePath)
                    put("AETHER_CONFIG", File(context.filesDir, "aether.toml").absolutePath)
                    put("AETHER_MASQUE_CONFIG", identityFile.absolutePath)
                    put("AETHER_PROTOCOL", if (enableMim) "mim" else "masque")
                    put("AETHER_PERF_PROFILE", perfProfile)
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
