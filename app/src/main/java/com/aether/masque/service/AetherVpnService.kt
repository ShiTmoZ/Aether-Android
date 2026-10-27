package com.aether.masque.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aether.masque.core.AetherProcessService
import com.aether.masque.ui.MainActivity
import com.v2ray.ang.service.TProxyService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AetherVpnService : VpnService() {

    enum class ConnectionStatus {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    companion object {
        private const val TAG = "AetherVpnService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "aether_masque_channel"

        const val ACTION_CONNECT = "com.aether.masque.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.aether.masque.ACTION_DISCONNECT"
        const val EXTRA_PEER = "com.aether.masque.EXTRA_PEER"
        const val EXTRA_FRESH_SCAN = "com.aether.masque.EXTRA_FRESH_SCAN"

        private val _status = MutableStateFlow(ConnectionStatus.DISCONNECTED)
        val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

        private val _rtt = MutableStateFlow("-- ms")
        val rtt: StateFlow<String> = _rtt.asStateFlow()

        private val _gateway = MutableStateFlow("162.159.198.x:443")
        val gateway: StateFlow<String> = _gateway.asStateFlow()

        private val _logLines = MutableStateFlow<List<String>>(emptyList())
        val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

        private val _connectedSeconds = MutableStateFlow(0L)
        val connectedSeconds: StateFlow<Long> = _connectedSeconds.asStateFlow()

        fun startService(context: Context, peer: String? = null, freshScan: Boolean = false) {
            val intent = Intent(context, AetherVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_PEER, peer)
                putExtra(EXTRA_FRESH_SCAN, freshScan)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, AetherVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }
    }

    private var processService: AetherProcessService? = null
    private var tProxyService: TProxyService? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var durationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val peer = intent.getStringExtra(EXTRA_PEER)
                val freshScan = intent.getBooleanExtra(EXTRA_FRESH_SCAN, false)
                startVpn(peer, freshScan)
            }
            ACTION_DISCONNECT -> {
                stopVpn()
            }
        }
        return START_NOT_STICKY
    }

    private fun startVpn(peer: String? = null, freshScan: Boolean = false) {
        if (_status.value == ConnectionStatus.CONNECTING || _status.value == ConnectionStatus.CONNECTED) {
            // If already running or connecting, stop and restart
            stopVpn()
        }

        _status.value = ConnectionStatus.CONNECTING
        val targetLabel = if (!peer.isNullOrBlank() && peer != "auto") peer else "Anycast Mesh"
        startForeground(NOTIFICATION_ID, buildNotification("Connecting to $targetLabel..."))

        val proc = AetherProcessService(this, peer, freshScan)
        processService = proc

        // Listen for logs
        serviceScope.launch {
            proc.logs.collect { line ->
                val current = _logLines.value.toMutableList()
                if (current.size > 300) {
                    current.removeAt(0)
                }
                current.add(line)
                _logLines.value = current

                if (proc.lastRtt != "-- ms") {
                    _rtt.value = proc.lastRtt
                }
                if (proc.lastGateway != "Cloudflare Edge") {
                    _gateway.value = proc.lastGateway
                }
            }
        }

        proc.start(
            onReady = {
                setupTunInterface()
            },
            onError = { errMsg ->
                Log.e(TAG, "Aether core error: $errMsg")
                _status.value = ConnectionStatus.ERROR
                stopVpn()
            }
        )
    }

    private fun setupTunInterface() {
        try {
            val builder = Builder()
                .setMtu(1360)
                .addAddress("172.19.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("1.0.0.1")
                .setSession("Aether MASQUE")
                .setBlocking(false)

            // Critical: bypass our own package so Aether core connects directly without routing loop
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.w(TAG, "Could not disallow self package", e)
            }

            val pfd = builder.establish()
            if (pfd == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                _status.value = ConnectionStatus.ERROR
                stopVpn()
                return
            }

            vpnInterface = pfd
            val proxy = TProxyService(this, pfd, AetherProcessService.SOCKS_PORT)
            tProxyService = proxy
            proxy.start()

            _status.value = ConnectionStatus.CONNECTED
            startForeground(NOTIFICATION_ID, buildNotification("Connected · Cloudflare MASQUE H2 (High Perf)"))

            // Start timer
            _connectedSeconds.value = 0L
            durationJob?.cancel()
            durationJob = serviceScope.launch {
                while (isActive) {
                    delay(1000L)
                    _connectedSeconds.value += 1
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up TUN", e)
            _status.value = ConnectionStatus.ERROR
            stopVpn()
        }
    }

    private fun stopVpn() {
        durationJob?.cancel()
        durationJob = null

        try {
            tProxyService?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TProxy", e)
        }
        tProxyService = null

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN fd", e)
        }
        vpnInterface = null

        processService?.stop()
        processService = null

        _status.value = ConnectionStatus.DISCONNECTED
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Aether MASQUE Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live status of Aether MASQUE VPN connection"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingLaunch = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, AetherVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val pendingDisconnect = PendingIntent.getService(
            this, 1, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aether MASQUE")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingLaunch)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", pendingDisconnect)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
