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
    private val forceFreshScan: Boolean = false
) {
    companion object {
        private const val TAG = "AetherProcessService"
        const val SOCKS_PORT = 10808
        const val BINARY_NAME = "libaether.so"

        private val RTT_REGEX = Pattern.compile("([0-9.]+ms)")
        private val GW_REGEX = Pattern.compile("(?:\\[[0-9a-fA-F:]+\\]|\\b[0-9.]+):[0-9]+")
    }

    private var process: Process? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 500)
    val logs: SharedFlow<String> = _logs

    var lastRtt: String = "-- ms"
        private set
    var lastGateway: String = targetPeer ?: "Anycast Mesh"
        private set

    fun start(onReady: () -> Unit, onError: (String) -> Unit, onExit: (Int) -> Unit = {}) {
        scope.launch {
            try {
                val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
                if (!binary.exists()) {
                    val err = "Engine binary missing: ${binary.absolutePath}"
                    Log.e(TAG, err)
                    withContext(Dispatchers.Main) { onError(err) }
                    return@launch
                }

                if (!binary.canExecute()) {
                    binary.setExecutable(true, false)
                }

                // Native Aether MASQUE command-line matching Windows production engine
                val args = mutableListOf(
                    binary.absolutePath,
                    "--masque",
                    "--h2",
                    "--dual",
                    "--perf", "high",
                    "--bind", "127.0.0.1:$SOCKS_PORT"
                )

                if (!targetPeer.isNullOrBlank() && targetPeer != "auto") {
                    args.add("--peer")
                    args.add(targetPeer)
                    _logs.emit("[CORE] Direct Peering: locked to $targetPeer...")
                } else {
                    args.add("--scan")
                    args.add("verified")
                    if (forceFreshScan) {
                        args.add("--no-quick-reconnect")
                        _logs.emit("[CORE] Dynamic Mesh Hunt: sweeping Anycast candidate pool...")
                    } else {
                        args.add("--quick-reconnect")
                        _logs.emit("[CORE] Verified Anycast Mesh (High Performance)...")
                    }
                }

                Log.i(TAG, "Launching: ${args.joinToString(" ")}")

                val builder = ProcessBuilder(args)
                    .directory(context.filesDir)
                    .redirectErrorStream(true)

                builder.environment().apply {
                    put("HOME", context.filesDir.absolutePath)
                    put("TMPDIR", context.cacheDir.absolutePath)
                    put("AETHER_PROTOCOL", "masque")
                    put("AETHER_PERF_PROFILE", "high")
                }

                val proc = builder.start()
                process = proc

                var notifiedReady = false

                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                var line: String? = null
                while (isActive && proc.isAlive && reader.readLine().also { line = it } != null) {
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
                        val candidate = gwMatcher.group()
                        if (!candidate.startsWith("127.0.0.1")) {
                            lastGateway = candidate
                        }
                    }

                    // Trigger ready ONLY when socks5 server is actually listening
                    if (!notifiedReady && (text.contains("socks5 server listening", ignoreCase = true) ||
                                           text.contains("tunnel validated", ignoreCase = true) ||
                                           text.contains("127.0.0.1:$SOCKS_PORT"))) {
                        notifiedReady = true
                        withContext(Dispatchers.Main) {
                            onReady()
                        }
                    }
                }

                val exitCode = proc.waitFor()
                Log.w(TAG, "Aether process terminated with code $exitCode")
                _logs.emit("[CORE] Engine stopped (code $exitCode)")
                withContext(Dispatchers.Main) {
                    if (!notifiedReady) {
                        onError("Engine exited unexpectedly (code $exitCode)")
                    } else {
                        onExit(exitCode)
                    }
                }
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
