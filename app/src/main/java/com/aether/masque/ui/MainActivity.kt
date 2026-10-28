package com.aether.masque.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aether.masque.core.TunnelMode
import com.aether.masque.service.AetherVpnService
import com.aether.masque.service.AetherVpnService.ConnectionStatus

data class CleanEdge(
    val id: String,
    val name: String,
    val flag: String,
    val endpoint: String?,
    val country: String,
    val latencyHint: String
)

val CLEAN_EDGES = listOf(
    CleanEdge("auto", "Auto Turbo Anycast", "⚡", null, "Anycast Mesh", "Dynamic"),
    CleanEdge("de_frankfurt", "Frankfurt Alpha", "🇩🇪", "162.159.198.4:443", "Germany", "~140 ms"),
    CleanEdge("nl_amsterdam", "Amsterdam Direct", "🇳🇱", "162.159.198.198:443", "Netherlands", "~145 ms"),
    CleanEdge("se_stockholm", "Stockholm Edge", "🇸🇪", "162.159.192.1:443", "Sweden", "~155 ms"),
    CleanEdge("gb_london", "London Prime", "🇬🇧", "162.159.193.5:443", "United Kingdom", "~160 ms"),
    CleanEdge("fi_helsinki", "Helsinki Gateway", "🇫🇮", "162.159.199.60:443", "Finland", "~150 ms"),
    CleanEdge("pl_warsaw", "Warsaw Core", "🇵🇱", "162.159.199.133:443", "Poland", "~155 ms")
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AetherAppRoot()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AetherAppRoot() {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val prefs: SharedPreferences = remember {
        context.getSharedPreferences("aether_prefs", Context.MODE_PRIVATE)
    }

    var selectedEdgeId by remember {
        mutableStateOf(prefs.getString("selected_edge_id", "auto") ?: "auto")
    }
    val currentEdge = CLEAN_EDGES.find { it.id == selectedEdgeId } ?: CLEAN_EDGES[0]

    var tunnelMode by remember {
        mutableStateOf(
            try {
                TunnelMode.valueOf(prefs.getString("tunnel_mode", TunnelMode.GOOL.name) ?: TunnelMode.GOOL.name)
            } catch (e: Exception) {
                TunnelMode.GOOL
            }
        )
    }

    val status by AetherVpnService.status.collectAsState()
    val rtt by AetherVpnService.rtt.collectAsState()
    val gateway by AetherVpnService.gateway.collectAsState()
    val activeTunnelMode by AetherVpnService.activeTunnelMode.collectAsState()
    val logLines by AetherVpnService.logLines.collectAsState()
    val durationSeconds by AetherVpnService.connectedSeconds.collectAsState()

    var showLogs by remember { mutableStateOf(false) }
    var showEdgeSheet by remember { mutableStateOf(false) }

    // Launcher for VPN permission dialog
    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            AetherVpnService.startService(context, currentEdge.endpoint, false, tunnelMode)
        } else {
            Toast.makeText(context, "VPN permission is required to tunnel traffic", Toast.LENGTH_SHORT).show()
        }
    }

    val connectTunnel: (freshScan: Boolean) -> Unit = { freshScan ->
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            vpnLauncher.launch(prepareIntent)
        } else {
            AetherVpnService.startService(context, currentEdge.endpoint, freshScan, tunnelMode)
        }
    }

    val onToggle = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        when (status) {
            ConnectionStatus.DISCONNECTED, ConnectionStatus.ERROR -> {
                connectTunnel(false)
            }
            ConnectionStatus.CONNECTING, ConnectionStatus.CONNECTED -> {
                AetherVpnService.stopService(context)
            }
        }
    }

    val onHuntNext = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        Toast.makeText(context, "Hunting clean high-speed edge...", Toast.LENGTH_SHORT).show()
        connectTunnel(true)
    }

    val onSelectMode = { mode: TunnelMode ->
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        tunnelMode = mode
        prefs.edit().putString("tunnel_mode", mode.name).apply()
        if (status == ConnectionStatus.CONNECTED || status == ConnectionStatus.CONNECTING) {
            AetherVpnService.startService(context, currentEdge.endpoint, false, mode)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05070B))
    ) {
        // Subtle cyber background grid
        Canvas(modifier = Modifier.fillMaxSize()) {
            val step = 40.dp.toPx()
            for (x in 0..(size.width / step).toInt()) {
                drawLine(
                    color = Color.White.copy(alpha = 0.025f),
                    start = Offset(x * step, 0f),
                    end = Offset(x * step, size.height),
                    strokeWidth = 1f
                )
            }
            for (y in 0..(size.height / step).toInt()) {
                drawLine(
                    color = Color.White.copy(alpha = 0.025f),
                    start = Offset(0f, y * step),
                    end = Offset(size.width, y * step),
                    strokeWidth = 1f
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header Bar
            HeaderSection(status = status, mode = tunnelMode)

            // Center: Optic Lens Button & Edge Selector
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                OpticLensButton(
                    status = status,
                    mode = tunnelMode,
                    onClick = onToggle
                )

                Spacer(modifier = Modifier.height(12.dp))

                DurationDisplay(
                    status = status,
                    durationSeconds = durationSeconds
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Edge Selector Card & Hunt Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Selected Server Pill
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF0C1017))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                showEdgeSheet = true
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = currentEdge.flag, fontSize = 16.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = currentEdge.name,
                                        color = Color(0xFFF1F5F9),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = currentEdge.country,
                                        color = Color(0xFF64748B),
                                        fontSize = 10.sp
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Select Edge",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Hunt Next Edge Button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF082F49))
                            .border(1.dp, Color(0xFF0284C7).copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                            .clickable(onClick = onHuntNext)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = "Hunt",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "HUNT",
                                color = Color(0xFF38BDF8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Triple Tunnel Mode Segmented Selector (GOOL / DIRECT / MIM)
                TunnelModeSelector(
                    selectedMode = tunnelMode,
                    onSelectMode = onSelectMode
                )
            }

            // Bottom Section: Telemetry Bento + Collapsible Logs
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TelemetryBento(
                    status = status,
                    rtt = rtt,
                    gateway = gateway,
                    selectedEdge = currentEdge,
                    mode = tunnelMode
                )

                Spacer(modifier = Modifier.height(10.dp))

                LogDrawerTrigger(
                    logCount = logLines.size,
                    expanded = showLogs,
                    onToggle = { showLogs = !showLogs }
                )

                AnimatedVisibility(
                    visible = showLogs,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    DiagnosticLogWindow(
                        logs = logLines,
                        onCopy = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Aether Logs", logLines.joinToString("\n"))
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }

        // Clean Edge Bottom Sheet Modal
        if (showEdgeSheet) {
            ModalBottomSheet(
                onDismissRequest = { showEdgeSheet = false },
                containerColor = Color(0xFF070B12),
                dragHandle = {
                    Box(
                        modifier = Modifier
                            .padding(top = 10.dp, bottom = 6.dp)
                            .width(40.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.2f))
                    )
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                        .navigationBarsPadding()
                ) {
                    Text(
                        text = "SELECT CLOUDFLARE WARP GATEWAY",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(CLEAN_EDGES) { edge ->
                            val isSelected = edge.id == selectedEdgeId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) Color(0xFF0F172A) else Color(0xFF0C1017))
                                    .border(
                                        1.dp,
                                        if (isSelected) Color(0xFF38BDF8).copy(alpha = 0.6f) else Color.White.copy(alpha = 0.04f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        selectedEdgeId = edge.id
                                        prefs.edit().putString("selected_edge_id", edge.id).apply()
                                        showEdgeSheet = false
                                        if (status == ConnectionStatus.CONNECTED || status == ConnectionStatus.CONNECTING) {
                                            AetherVpnService.startService(context, edge.endpoint, false, tunnelMode)
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = edge.flag, fontSize = 20.sp)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = edge.name,
                                            color = if (isSelected) Color(0xFF38BDF8) else Color(0xFFF1F5F9),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = edge.endpoint ?: "Fastest Anycast Discovery",
                                            color = Color(0xFF64748B),
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }

                                Text(
                                    text = edge.latencyHint,
                                    color = if (isSelected) Color(0xFF00E676) else Color(0xFF94A3B8),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
fun TunnelModeSelector(
    selectedMode: TunnelMode,
    onSelectMode: (TunnelMode) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0C1017))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .padding(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TunnelMode.values().forEach { mode ->
                val isSelected = selectedMode == mode
                val (label, subtitle, activeColor, activeBg) = when (mode) {
                    TunnelMode.GOOL -> Quadruple(
                        "GOOL",
                        "Europe IP",
                        Color(0xFF10B981),
                        Color(0xFF064E3B).copy(alpha = 0.5f)
                    )
                    TunnelMode.DIRECT -> Quadruple(
                        "DIRECT",
                        "Ultra Fast",
                        Color(0xFF38BDF8),
                        Color(0xFF0369A1).copy(alpha = 0.5f)
                    )
                    TunnelMode.MIM -> Quadruple(
                        "MIM",
                        "Dual-Hop",
                        Color(0xFFA78BFA),
                        Color(0xFF4C1D95).copy(alpha = 0.5f)
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) activeBg else Color.Transparent)
                        .border(
                            1.dp,
                            if (isSelected) activeColor.copy(alpha = 0.8f) else Color.Transparent,
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { onSelectMode(mode) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = label,
                            color = if (isSelected) Color.White else Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            color = if (isSelected) activeColor else Color(0xFF64748B),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HeaderSection(status: ConnectionStatus, mode: TunnelMode) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "AETHER",
                color = Color(0xFFF1F5F9),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (title, color, tagBg, tagText) = when (mode) {
                    TunnelMode.GOOL -> Quadruple(
                        "GOOL WARP (EUROPE IP)",
                        Color(0xFF10B981),
                        Color(0xFF064E3B).copy(alpha = 0.5f),
                        "DE/NL"
                    )
                    TunnelMode.DIRECT -> Quadruple(
                        "CLOUDFLARE MASQUE H2",
                        Color(0xFF38BDF8),
                        Color(0xFF0369A1).copy(alpha = 0.4f),
                        "DIRECT"
                    )
                    TunnelMode.MIM -> Quadruple(
                        "MIM DUAL-HOP H2",
                        Color(0xFFC084FC),
                        Color(0xFF581C87).copy(alpha = 0.5f),
                        "CASCADED"
                    )
                }
                Text(
                    text = title,
                    color = color,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(tagBg)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = tagText,
                        color = Color.White,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Status Badge Pill
        val (bgColor, borderColor, textColor, label) = when (status) {
            ConnectionStatus.CONNECTED -> when (mode) {
                TunnelMode.GOOL -> Quadruple(Color(0xFF052E16), Color(0xFF10B981), Color(0xFF00E676), "GOOL ONLINE")
                TunnelMode.DIRECT -> Quadruple(Color(0xFF082F49), Color(0xFF0284C7), Color(0xFF38BDF8), "ONLINE · H2")
                TunnelMode.MIM -> Quadruple(Color(0xFF2E1065), Color(0xFFA78BFA), Color(0xFFC084FC), "MIM ONLINE")
            }
            ConnectionStatus.CONNECTING -> Quadruple(
                Color(0xFF082F49), Color(0xFF0284C7), Color(0xFF38BDF8), "CONNECTING..."
            )
            ConnectionStatus.ERROR -> Quadruple(
                Color(0xFF450A0A), Color(0xFFDC2626), Color(0xFFF87171), "ERROR"
            )
            ConnectionStatus.DISCONNECTED -> Quadruple(
                Color(0xFF1E293B), Color(0xFF334155), Color(0xFF94A3B8), "DISCONNECTED"
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(bgColor)
                .border(1.dp, borderColor, RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(textColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = label,
                    color = textColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
fun OpticLensButton(
    status: ConnectionStatus,
    mode: TunnelMode,
    onClick: () -> Unit
) {
    val isConnected = status == ConnectionStatus.CONNECTED
    val isConnecting = status == ConnectionStatus.CONNECTING

    // Infinite radar pulsing animation
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isConnected || isConnecting) 1.35f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = if (isConnected || isConnecting) 0.5f else 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(180.dp)
    ) {
        // Radar Wave
        if (isConnected || isConnecting) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .scale(pulseScale)
            ) {
                drawCircle(
                    color = when {
                        isConnected && mode == TunnelMode.GOOL -> Color(0xFF10B981).copy(alpha = pulseAlpha)
                        isConnected && mode == TunnelMode.MIM -> Color(0xFFA78BFA).copy(alpha = pulseAlpha)
                        isConnected -> Color(0xFF00E676).copy(alpha = pulseAlpha)
                        else -> Color(0xFF38BDF8).copy(alpha = pulseAlpha)
                    },
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        // Liquid Glass Meniscus Ring & Lens Core
        val lensBrush = when {
            isConnected && mode == TunnelMode.GOOL -> Brush.radialGradient(
                colors = listOf(Color(0xFF065F46), Color(0xFF064E3B), Color(0xFF022C22))
            )
            isConnected && mode == TunnelMode.MIM -> Brush.radialGradient(
                colors = listOf(Color(0xFF4C1D95), Color(0xFF2E1065), Color(0xFF130D24))
            )
            isConnected -> Brush.radialGradient(
                colors = listOf(Color(0xFF064E3B), Color(0xFF022C22), Color(0xFF011A14))
            )
            isConnecting -> Brush.radialGradient(
                colors = listOf(Color(0xFF0369A1), Color(0xFF082F49), Color(0xFF031627))
            )
            else -> Brush.radialGradient(
                colors = listOf(Color(0xFF1E293B), Color(0xFF0F172A), Color(0xFF070B12))
            )
        }

        val borderBrush = when {
            isConnected && mode == TunnelMode.GOOL -> Brush.linearGradient(
                listOf(Color(0xFF34D399), Color(0xFF10B981).copy(alpha = 0.4f), Color.White.copy(alpha = 0.8f))
            )
            isConnected && mode == TunnelMode.MIM -> Brush.linearGradient(
                listOf(Color(0xFFC084FC), Color(0xFF7C3AED).copy(alpha = 0.4f), Color.White.copy(alpha = 0.8f))
            )
            isConnected -> Brush.linearGradient(
                listOf(Color(0xFF00E676), Color(0xFF00E676).copy(alpha = 0.3f), Color.White.copy(alpha = 0.8f))
            )
            isConnecting -> Brush.linearGradient(
                listOf(Color(0xFF38BDF8), Color(0xFF0284C7).copy(alpha = 0.4f), Color.White.copy(alpha = 0.8f))
            )
            else -> Brush.linearGradient(
                listOf(Color.White.copy(alpha = 0.4f), Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.15f))
            )
        }

        Box(
            modifier = Modifier
                .size(144.dp)
                .clip(CircleShape)
                .background(lensBrush)
                .border(2.dp, borderBrush, CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PowerSettingsNew,
                contentDescription = "Toggle",
                tint = when {
                    isConnected && mode == TunnelMode.GOOL -> Color(0xFF6EE7B7)
                    isConnected && mode == TunnelMode.MIM -> Color(0xFFE9D5FF)
                    isConnected -> Color(0xFF00E676)
                    isConnecting -> Color(0xFF38BDF8)
                    else -> Color(0xFF64748B)
                },
                modifier = Modifier.size(50.dp)
            )
        }
    }
}

@Composable
fun DurationDisplay(status: ConnectionStatus, durationSeconds: Long) {
    if (status == ConnectionStatus.CONNECTED) {
        val hours = durationSeconds / 3600
        val minutes = (durationSeconds % 3600) / 60
        val seconds = durationSeconds % 60
        val timeString = String.format("%02d:%02d:%02d", hours, minutes, seconds)

        Text(
            text = timeString,
            color = Color(0xFFCBD5E1),
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp
        )
    } else {
        Text(
            text = if (status == ConnectionStatus.CONNECTING) "ESTABLISHING SECURE TUNNEL..." else "TAP TO CONNECT",
            color = Color(0xFF64748B),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.5.sp
        )
    }
}

@Composable
fun TelemetryBento(
    status: ConnectionStatus,
    rtt: String,
    gateway: String,
    selectedEdge: CleanEdge,
    mode: TunnelMode
) {
    val isOnline = status == ConnectionStatus.CONNECTED

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF0C1017).copy(alpha = 0.9f))
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.02f))
                ),
                RoundedCornerShape(18.dp)
            )
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BentoItem(
                modifier = Modifier.weight(1f),
                title = "TUNNEL MODE",
                value = when (mode) {
                    TunnelMode.GOOL -> "GOOL (WARP)"
                    TunnelMode.DIRECT -> "MASQUE H2"
                    TunnelMode.MIM -> "MIM DUAL-HOP"
                },
                accentColor = when (mode) {
                    TunnelMode.GOOL -> Color(0xFF10B981)
                    TunnelMode.DIRECT -> Color(0xFF38BDF8)
                    TunnelMode.MIM -> Color(0xFFA78BFA)
                }
            )
            BentoItem(
                modifier = Modifier.weight(1f),
                title = "LATENCY (RTT)",
                value = if (isOnline) rtt else "-- ms",
                accentColor = if (isOnline) Color(0xFF00E676) else Color(0xFF64748B)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BentoItem(
                modifier = Modifier.weight(1f),
                title = "EGRESS / EXIT IP",
                value = when (mode) {
                    TunnelMode.GOOL -> "GERMANY / NL"
                    TunnelMode.DIRECT -> "LOCALIZED PRIVACY"
                    TunnelMode.MIM -> "EUROPE (DE/NL)"
                },
                accentColor = when (mode) {
                    TunnelMode.GOOL -> Color(0xFF34D399)
                    TunnelMode.DIRECT -> Color(0xFF94A3B8)
                    TunnelMode.MIM -> Color(0xFFC084FC)
                }
            )
            BentoItem(
                modifier = Modifier.weight(1f),
                title = "INGRESS EDGE",
                value = if (isOnline) gateway else (selectedEdge.endpoint ?: "Auto Mesh"),
                accentColor = Color(0xFFA78BFA)
            )
        }
    }
}

@Composable
fun BentoItem(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    accentColor: Color
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF080C13))
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Column {
            Text(
                text = title,
                color = Color(0xFF64748B),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = value,
                color = accentColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }
    }
}

@Composable
fun LogDrawerTrigger(
    logCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0C1017))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = null,
                tint = Color(0xFF94A3B8),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Diagnostic Console",
                color = Color(0xFFCBD5E1),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF1E293B))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "$logCount",
                    color = Color(0xFF94A3B8),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = Color(0xFF94A3B8),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
fun DiagnosticLogWindow(
    logs: List<String>,
    onCopy: () -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF030508))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
            .padding(10.dp)
    ) {
        if (logs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Awaiting engine output...",
                    color = Color(0xFF475569),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                items(logs) { line ->
                    val color = when {
                        line.contains("[CORE]") -> Color(0xFF8DB4F5)
                        line.contains("[MASQUE]") || line.contains("MIM") -> Color(0xFFC084FC)
                        line.contains("[TUN]") -> Color(0xFF7FD1BE)
                        line.contains("[ERR]") || line.contains("error") -> Color(0xFFFF6B6B)
                        else -> Color(0xFF94A3B8)
                    }
                    Text(
                        text = line,
                        color = color,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 14.sp
                    )
                }
            }
        }

        // Copy button
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF1E293B).copy(alpha = 0.8f))
                .clickable(onClick = onCopy)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = "COPY",
                color = Color(0xFFCBD5E1),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
