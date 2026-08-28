package com.example.phonemic.ui.screens

import android.graphics.Bitmap
import android.net.wifi.p2p.WifiP2pDevice
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.phonemic.ui.components.AudioMeter
import com.example.phonemic.utils.NetworkUtils

enum class ConnectionTab {
    WIFI_LAN,
    WIFI_DIRECT,
    BLUETOOTH
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isRecording: Boolean,
    onToggleRecording: () -> Unit,
    amplitude: Float,
    waveform: FloatArray,
    localIpAddress: String?,
    gain: Float,
    onGainChanged: (Float) -> Unit,
    isMuted: Boolean,
    onMuteToggled: (Boolean) -> Unit,
    isNoiseSuppressionEnabled: Boolean,
    onNoiseSuppressionToggled: (Boolean) -> Unit,
    // Wi-Fi Direct parameters
    p2pPeers: List<WifiP2pDevice>,
    p2pStatus: String,
    onDiscoverP2pPeers: () -> Unit,
    onConnectP2pDevice: (WifiP2pDevice) -> Unit,
    // Bluetooth parameters
    bluetoothStatus: String,
    isScoActive: Boolean,
    onToggleSco: (Boolean) -> Unit
) {
    var selectedTab by remember { mutableStateOf(ConnectionTab.WIFI_LAN) }
    var showQrDialog by remember { mutableStateOf(false) }

    val webUrl = localIpAddress?.let { "http://$it:8080" } ?: "Not Connected to Wi-Fi"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "PhoneMic Wireless",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F172A)
                )
            )
        },
        containerColor = Color(0xFF0F172A)
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // Tab Selector
            item {
                TabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    containerColor = Color(0xFF1E293B),
                    contentColor = Color(0xFF38BDF8),
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                ) {
                    Tab(
                        selected = selectedTab == ConnectionTab.WIFI_LAN,
                        onClick = { selectedTab = ConnectionTab.WIFI_LAN },
                        text = { Text("Wi-Fi LAN", fontSize = 12.sp) },
                        icon = { Icon(Icons.Default.Wifi, contentDescription = null) }
                    )
                    Tab(
                        selected = selectedTab == ConnectionTab.WIFI_DIRECT,
                        onClick = { selectedTab = ConnectionTab.WIFI_DIRECT },
                        text = { Text("Wi-Fi P2P", fontSize = 12.sp) },
                        icon = { Icon(Icons.Default.WifiTethering, contentDescription = null) }
                    )
                    Tab(
                        selected = selectedTab == ConnectionTab.BLUETOOTH,
                        onClick = { selectedTab = ConnectionTab.BLUETOOTH },
                        text = { Text("Bluetooth", fontSize = 12.sp) },
                        icon = { Icon(Icons.Default.Bluetooth, contentDescription = null) }
                    )
                }
            }

            // Central Mic Toggle Button
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        IconButton(
                            onClick = onToggleRecording,
                            modifier = Modifier
                                .size(96.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isRecording) Color(0xFFEF4444) else Color(0xFF0284C7)
                                )
                        ) {
                            Icon(
                                imageVector = if (isRecording) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = "Toggle Recording",
                                tint = Color.White,
                                modifier = Modifier.size(48.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = if (isRecording) "MICROPHONE LIVE" else "MICROPHONE OFF",
                            fontWeight = FontWeight.Bold,
                            color = if (isRecording) Color(0xFF4ADE80) else Color(0xFF94A3B8),
                            fontSize = 14.sp
                        )

                        Text(
                            text = if (isRecording) "Streaming audio over selected connection" else "Tap button above to start broadcasting",
                            color = Color(0xFF64748B),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // Live Audio Meter
            item {
                AudioMeter(
                    amplitude = amplitude,
                    waveform = waveform
                )
            }

            // Connection Mode Details Card
            item {
                when (selectedTab) {
                    ConnectionTab.WIFI_LAN -> {
                        WifiLanCard(
                            webUrl = webUrl,
                            localIp = localIpAddress,
                            onShowQrCode = { showQrDialog = true }
                        )
                    }
                    ConnectionTab.WIFI_DIRECT -> {
                        WifiDirectCard(
                            p2pStatus = p2pStatus,
                            peers = p2pPeers,
                            onDiscover = onDiscoverP2pPeers,
                            onConnect = onConnectP2pDevice
                        )
                    }
                    ConnectionTab.BLUETOOTH -> {
                        BluetoothCard(
                            status = bluetoothStatus,
                            isScoActive = isScoActive,
                            onToggleSco = onToggleSco
                        )
                    }
                }
            }

            // Audio Controls & DSP Settings Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "AUDIO CONTROLS & DSP",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )

                        // Gain Slider
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Microphone Gain", color = Color.White, fontSize = 14.sp)
                                Text("${(gain * 100).toInt()}%", color = Color(0xFF38BDF8), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = gain,
                                onValueChange = onGainChanged,
                                valueRange = 0.0f..3.0f,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFF38BDF8),
                                    activeTrackColor = Color(0xFF0284C7)
                                )
                            )
                        }

                        Divider(color = Color(0xFF334155))

                        // Mute Switch
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                    contentDescription = null,
                                    tint = Color.White
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Mute Microphone", color = Color.White, fontSize = 14.sp)
                            }
                            Switch(
                                checked = isMuted,
                                onCheckedChange = onMuteToggled,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFFEF4444),
                                    checkedTrackColor = Color(0xFF991B1B)
                                )
                            )
                        }

                        Divider(color = Color(0xFF334155))

                        // Noise Suppression Switch
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = Color.White
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Noise Suppression (DSP)", color = Color.White, fontSize = 14.sp)
                            }
                            Switch(
                                checked = isNoiseSuppressionEnabled,
                                onCheckedChange = onNoiseSuppressionToggled,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF10B981),
                                    checkedTrackColor = Color(0xFF065F46)
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    // QR Code Dialog Popup
    if (showQrDialog && localIpAddress != null) {
        val qrBitmap = remember(webUrl) { NetworkUtils.generateQrCode(webUrl) }
        Dialog(onDismissRequest = { showQrDialog = false }) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                modifier = Modifier.padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Scan QR to Open Live Stream",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    qrBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier
                                .size(220.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = webUrl,
                        color = Color(0xFF38BDF8),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { showQrDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
fun WifiLanCard(
    webUrl: String,
    localIp: String?,
    onShowQrCode: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Wifi, contentDescription = null, tint = Color(0xFF38BDF8))
                Spacer(modifier = Modifier.width(8.dp))
                Text("SAME WI-FI STREAMING", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
            }

            Text(
                text = "Open this Web URL in any PC, Mac, or Smartphone browser to play live audio:",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = webUrl,
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    if (localIp != null) {
                        IconButton(onClick = onShowQrCode) {
                            Icon(Icons.Default.QrCode, contentDescription = "Show QR Code", tint = Color.White)
                        }
                    }
                }
            }

            Text(
                text = "⚡ PC Low Latency UDP Target: IP $localIp | Port 50005",
                color = Color(0xFF4ADE80),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
fun WifiDirectCard(
    p2pStatus: String,
    peers: List<WifiP2pDevice>,
    onDiscover: () -> Unit,
    onConnect: (WifiP2pDevice) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.WifiTethering, contentDescription = null, tint = Color(0xFF38BDF8))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("WI-FI DIRECT (P2P)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                }
                Button(
                    onClick = onDiscover,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("Scan Peers", fontSize = 12.sp)
                }
            }

            Text(text = "Status: $p2pStatus", color = Color(0xFF94A3B8), fontSize = 12.sp)

            if (peers.isNotEmpty()) {
                Text("Discovered Nearby Devices:", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                peers.forEach { device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F172A))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(device.deviceName ?: "Unknown Device", color = Color.White, fontSize = 13.sp)
                        Button(
                            onClick = { onConnect(device) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                        ) {
                            Text("Connect", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BluetoothCard(
    status: String,
    isScoActive: Boolean,
    onToggleSco: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bluetooth, contentDescription = null, tint = Color(0xFF38BDF8))
                Spacer(modifier = Modifier.width(8.dp))
                Text("BLUETOOTH AUDIO", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
            }

            Text(text = "Status: $status", color = Color(0xFF94A3B8), fontSize = 12.sp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Bluetooth Headset/SCO Routing", color = Color.White, fontSize = 13.sp)
                    Text("Route phone mic to paired BT headset/speaker", color = Color(0xFF64748B), fontSize = 11.sp)
                }
                Switch(
                    checked = isScoActive,
                    onCheckedChange = onToggleSco,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF38BDF8))
                )
            }
        }
    }
}
