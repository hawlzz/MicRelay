package com.example.phonemic

import android.Manifest
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.example.phonemic.audio.AudioRecorderManager
import com.example.phonemic.bluetooth.BluetoothMicManager
import com.example.phonemic.network.HttpAudioServer
import com.example.phonemic.network.UdpStreamer
import com.example.phonemic.network.WifiP2pConnectionManager
import com.example.phonemic.ui.screens.MainScreen
import com.example.phonemic.utils.NetworkUtils
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

    private lateinit var audioRecorderManager: AudioRecorderManager
    private lateinit var httpAudioServer: HttpAudioServer
    private lateinit var udpStreamer: UdpStreamer
    private lateinit var wifiP2pManager: WifiP2pConnectionManager
    private lateinit var bluetoothManager: BluetoothMicManager

    private var isRecordingState = mutableStateOf(false)
    private var amplitudeState = mutableStateOf(0f)
    private var waveformState = mutableStateOf(FloatArray(30) { 0f })
    private var localIpState = mutableStateOf<String?>(null)

    private var gainState = mutableStateOf(1.0f)
    private var isMutedState = mutableStateOf(false)
    private var isNoiseSuppressionState = mutableStateOf(true)

    private var p2pPeersState = mutableStateOf<List<WifiP2pDevice>>(emptyList())
    private var p2pStatusState = mutableStateOf("Disconnected")

    private var bluetoothStatusState = mutableStateOf("Ready")
    private var isScoActiveState = mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (recordAudioGranted) {
            startAudioAndServers()
        } else {
            Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Managers
        audioRecorderManager = AudioRecorderManager()
        httpAudioServer = HttpAudioServer(port = 8080)
        udpStreamer = UdpStreamer()
        wifiP2pManager = WifiP2pConnectionManager(this)
        bluetoothManager = BluetoothMicManager(this)

        localIpState.value = NetworkUtils.getLocalIpAddress()

        // Set up Wi-Fi Direct callbacks
        wifiP2pManager.onPeersUpdated = { peers ->
            p2pPeersState.value = peers
        }
        wifiP2pManager.onConnectionStateChanged = { connected, info ->
            p2pStatusState.value = if (connected) "Connected: ${info ?: "Active"}" else "Disconnected"
        }

        // Set up Bluetooth callbacks
        bluetoothManager.onStatusChanged = { status ->
            bluetoothStatusState.value = status
            isScoActiveState.value = bluetoothManager.isScoActive
        }

        // Connect Audio Recorder output stream to all network targets
        audioRecorderManager.addAudioDataListener { buffer, length ->
            httpAudioServer.onAudioDataReceived(buffer, length)
            udpStreamer.sendAudioFrame(buffer, length)
            wifiP2pManager.sendAudioFrame(buffer, length)
            bluetoothManager.sendAudioFrame(buffer, length)
        }

        wifiP2pManager.initialize()
        bluetoothManager.startSppServer()

        setContent {
            val isRecording by remember { isRecordingState }
            val amplitude by remember { amplitudeState }
            val waveform by remember { waveformState }
            val localIp by remember { localIpState }

            val gain by remember { gainState }
            val isMuted by remember { isMutedState }
            val isNoiseSuppression by remember { isNoiseSuppressionState }

            val p2pPeers by remember { p2pPeersState }
            val p2pStatus by remember { p2pStatusState }

            val bluetoothStatus by remember { bluetoothStatusState }
            val isScoActive by remember { isScoActiveState }

            // Observe Audio Visualizer State
            LaunchedEffect(Unit) {
                audioRecorderManager.visualizerState.amplitude.collectLatest { amp ->
                    amplitudeState.value = amp
                }
            }
            LaunchedEffect(Unit) {
                audioRecorderManager.visualizerState.waveform.collectLatest { wave ->
                    waveformState.value = wave
                }
            }

            MainScreen(
                isRecording = isRecording,
                onToggleRecording = { toggleRecording() },
                amplitude = amplitude,
                waveform = waveform,
                localIpAddress = localIp,
                gain = gain,
                onGainChanged = { newGain ->
                    gainState.value = newGain
                    audioRecorderManager.gainMultiplier = newGain
                },
                isMuted = isMuted,
                onMuteToggled = { muted ->
                    isMutedState.value = muted
                    audioRecorderManager.isMuted = muted
                },
                isNoiseSuppressionEnabled = isNoiseSuppression,
                onNoiseSuppressionToggled = { ns ->
                    isNoiseSuppressionState.value = ns
                    audioRecorderManager.isNoiseSuppressionEnabled = ns
                },
                p2pPeers = p2pPeers,
                p2pStatus = p2pStatus,
                onDiscoverP2pPeers = { wifiP2pManager.discoverPeers() },
                onConnectP2pDevice = { device -> wifiP2pManager.connectToDevice(device) },
                bluetoothStatus = bluetoothStatus,
                isScoActive = isScoActive,
                onToggleSco = { enable ->
                    bluetoothManager.toggleBluetoothSco(enable)
                    isScoActiveState.value = bluetoothManager.isScoActive
                }
            )
        }

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)

        val ungrantedPermissions = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungrantedPermissions.isNotEmpty()) {
            permissionLauncher.launch(ungrantedPermissions.toTypedArray())
        } else {
            startAudioAndServers()
        }
    }

    private fun startAudioAndServers() {
        try {
            if (!httpAudioServer.wasStarted()) {
                httpAudioServer.start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun toggleRecording() {
        if (isRecordingState.value) {
            audioRecorderManager.stopRecording()
            udpStreamer.stopStreaming()
            isRecordingState.value = false
        } else {
            val started = audioRecorderManager.startRecording()
            if (started) {
                localIpState.value = NetworkUtils.getLocalIpAddress()
                localIpState.value?.let { ip ->
                    udpStreamer.startStreaming(targetIp = ip, port = 50005)
                }
                isRecordingState.value = true
            } else {
                Toast.makeText(this, "Failed to start microphone recording", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioRecorderManager.stopRecording()
        httpAudioServer.stop()
        udpStreamer.stopStreaming()
        wifiP2pManager.stopP2p()
        bluetoothManager.stopBluetooth()
    }
}
