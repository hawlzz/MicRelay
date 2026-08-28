package com.example.phonemic.network

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import kotlinx.coroutines.*
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

class WifiP2pConnectionManager(
    private val context: Context
) : WifiP2pManager.PeerListListener, WifiP2pManager.ConnectionInfoListener {

    companion object {
        private const val TAG = "WifiP2pManager"
        const val P2P_SERVER_PORT = 8888
    }

    private val wifiP2pManager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }

    private var channel: WifiP2pManager.Channel? = null
    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var outputStream: OutputStream? = null

    private val peersList = mutableListOf<WifiP2pDevice>()
    private var isReceiverRegistered = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    var isP2pConnected = false
        private set

    var onPeersUpdated: ((List<WifiP2pDevice>) -> Unit)? = null
    var onConnectionStateChanged: ((Boolean, String?) -> Unit)? = null

    fun initialize() {
        if (wifiP2pManager != null) {
            channel = wifiP2pManager?.initialize(context, context.mainLooper, null)
            registerReceiver()
        }
    }

    fun registerReceiver() {
        if (!isReceiverRegistered && channel != null) {
            context.registerReceiver(p2pReceiver, intentFilter)
            isReceiverRegistered = true
        }
    }

    fun unregisterReceiver() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(p2pReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Unregistering receiver failed", e)
            }
            isReceiverRegistered = false
        }
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        channel?.let { ch ->
            wifiP2pManager?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "Wi-Fi Direct Peer Discovery Started")
                }

                override fun onFailure(reasonCode: Int) {
                    Log.e(TAG, "Wi-Fi Direct Peer Discovery Failed: $reasonCode")
                }
            })
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }

        channel?.let { ch ->
            wifiP2pManager?.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "Connecting to P2P Device: ${device.deviceName}")
                }

                override fun onFailure(reason: Int) {
                    Log.e(TAG, "P2P Connection failed: $reason")
                }
            })
        }
    }

    override fun onPeersAvailable(peers: WifiP2pDeviceList?) {
        peersList.clear()
        if (peers != null) {
            peersList.addAll(peers.deviceList)
        }
        onPeersUpdated?.invoke(peersList)
    }

    override fun onConnectionInfoAvailable(info: WifiP2pInfo?) {
        if (info == null) return

        if (info.groupFormed && info.isGroupOwner) {
            Log.i(TAG, "Device is P2P Group Owner. Starting P2P Socket Server...")
            startP2pServerSocket()
        } else if (info.groupFormed) {
            val groupOwnerIp = info.groupOwnerAddress.hostAddress
            Log.i(TAG, "Connected to P2P Group Owner at $groupOwnerIp")
            onConnectionStateChanged?.invoke(true, groupOwnerIp)
        } else {
            isP2pConnected = false
            onConnectionStateChanged?.invoke(false, null)
        }
    }

    private fun startP2pServerSocket() {
        scope.launch {
            try {
                serverSocket = ServerSocket(P2P_SERVER_PORT)
                isP2pConnected = true
                onConnectionStateChanged?.invoke(true, "P2P Server Active (Port $P2P_SERVER_PORT)")

                Log.i(TAG, "P2P Server Socket waiting for client connection...")
                clientSocket = serverSocket?.accept()
                outputStream = clientSocket?.getOutputStream()
                Log.i(TAG, "P2P Client Connected: ${clientSocket?.inetAddress?.hostAddress}")
            } catch (e: Exception) {
                Log.e(TAG, "P2P Server Socket Error", e)
                isP2pConnected = false
                onConnectionStateChanged?.invoke(false, null)
            }
        }
    }

    fun sendAudioFrame(buffer: ByteArray, length: Int) {
        if (!isP2pConnected || outputStream == null) return

        scope.launch {
            try {
                outputStream?.write(buffer, 0, length)
                outputStream?.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Error writing to P2P socket stream", e)
            }
        }
    }

    fun stopP2p() {
        unregisterReceiver()
        try {
            outputStream?.close()
            clientSocket?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing P2P sockets", e)
        } finally {
            outputStream = null
            clientSocket = null
            serverSocket = null
            isP2pConnected = false
        }
    }

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val isEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    Log.d(TAG, "P2P State Enabled: $isEnabled")
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (channel != null) {
                        wifiP2pManager?.requestPeers(channel, this@WifiP2pConnectionManager)
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    if (channel != null) {
                        wifiP2pManager?.requestConnectionInfo(channel, this@WifiP2pConnectionManager)
                    }
                }
            }
        }
    }
}
