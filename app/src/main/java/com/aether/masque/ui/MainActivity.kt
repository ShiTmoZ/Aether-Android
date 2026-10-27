package com.aether.masque.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import com.aether.masque.service.AetherVpnService
import com.aether.masque.service.AetherVpnService.ConnectionStatus

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AetherAppRoot()
        }
    }
}

@Composable
fun AetherAppRoot() {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val status by AetherVpnService.status.collectAsState()
    val rtt by AetherVpnService.rtt.collectAsState()
    val gateway by AetherVpnService.gateway.collectAsState()
    val logLines by AetherVpnService.logLines.collectAsState()
    val durationSeconds by AetherVpnService.connectedSeconds.collectAsState()

    var showLogs by remember { mutableStateOf(false) }

    // Launcher for VPN permission dialog
    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            AetherVpnService.startService(context)
        } else {
            Toast.makeText(context, "VPN permission is required to tunnel traffic", Toast.LENGTH_SHORT).show()
        }
    }

    val onToggle = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        when (status) {
            ConnectionStatus.DISCONNECTED, ConnectionStatus.ERROR -> {
                val prepareIntent = VpnService.prepare(context)
                if (prepareIntent != null) {
                    vpnLauncher.launch(prepareIntent)
                } else {
                    AetherVpnService.startService(context)
                }
            }
            ConnectionStatus.CONNECTING, ConnectionStatus.CONNECTED -> {
                AetherVpnService.stopService(context)
            }
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
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header Bar
            HeaderSection(status = status)

            // Center: Optic Lens Button & Status
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                OpticLensButton(
                    status = status,
                    onClick = onToggle
                )

                Spacer(modifier = Modifier.height(20.dp))

                DurationDisplay(
                    status = status,
                    durationSeconds = durationSeconds
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
                    gateway = gateway
                )

                Spacer(modifier = Modifier.height(14.dp))

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
    }
}

@Composable
fun HeaderSection(status: ConnectionStatus) {
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
            Text(
                text = "CLOUDFLARE MASQUE H2",
                color = Color(0xFF38BDF8),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            )
        }

        // Status Badge Pill
        val (bgColor, borderColor, textColor, label) = when (status) {
            ConnectionStatus.CONNECTED -> Quadruple(
                Color(0xFF052E16), Color(0xFF10B981), Color(0xFF00E676), "ONLINE · TURBO"
            )
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
        modifier = Modifier.size(190.dp)
    ) {
        // Radar Wave
        if (isConnected || isConnecting) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .scale(pulseScale)
            ) {
                drawCircle(
                    color = if (isConnected) Color(0xFF00E676).copy(alpha = pulseAlpha)
                    else Color(0xFF38BDF8).copy(alpha = pulseAlpha),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        // Liquid Glass Meniscus Ring & Lens Core
        val lensBrush = when {
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
                .size(150.dp)
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
                    isConnected -> Color(0xFF00E676)
                    isConnecting -> Color(0xFF38BDF8)
                    else -> Color(0xFF64748B)
                },
                modifier = Modifier.size(54.dp)
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
            text = if (status == ConnectionStatus.CONNECTING) "ESTABLISHING TUNNEL..." else "TAP TO CONNECT",
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
    gateway: String
) {
    val isOnline = status == ConnectionStatus.CONNECTED

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF0C1017).copy(alpha = 0.9f))
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.15f), Color.White.copy(alpha = 0.03f))
                ),
                RoundedCornerShape(20.dp)
            )
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BentoItem(
                modifier = Modifier.weight(1f),
                title = "LATENCY (RTT)",
                value = if (isOnline) rtt else "-- ms",
                accentColor = if (isOnline) Color(0xFF00E676) else Color(0xFF64748B)
            )
            BentoItem(
                modifier = Modifier.weight(1f),
                title = "PROTOCOL",
                value = "MASQUE H2",
                accentColor = Color(0xFF38BDF8)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BentoItem(
                modifier = Modifier.weight(1f),
                title = "GATEWAY",
                value = if (isOnline) gateway else "162.159.x.x:443",
                accentColor = Color(0xFFA78BFA)
            )
            BentoItem(
                modifier = Modifier.weight(1f),
                title = "MTU / LOSSLESS",
                value = "1360 (Optimal)",
                accentColor = Color(0xFF34D399)
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
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF080C13))
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Column {
            Text(
                text = title,
                color = Color(0xFF64748B),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                color = accentColor,
                fontSize = 13.sp,
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
            .padding(horizontal = 14.dp, vertical = 10.dp),
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
                fontSize = 12.sp,
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
            .height(200.dp)
            .padding(top = 8.dp)
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
                        line.contains("[MASQUE]") -> Color(0xFFB7A4F2)
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
