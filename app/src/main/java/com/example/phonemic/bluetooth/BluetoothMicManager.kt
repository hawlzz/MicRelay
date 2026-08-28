package com.example.phonemic.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.*
import java.io.OutputStream
import java.util.UUID

class BluetoothMicManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "BluetoothMicManager"
        private const val SERVICE_NAME = "PhoneMicBluetooth"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter
    }

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    @Volatile
    var isBluetoothConnected = false
        private set

    @Volatile
    var isScoActive = false
        private set

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var onStatusChanged: ((String) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun startSppServer(): Boolean {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            onStatusChanged?.invoke("Bluetooth disabled or not available")
            return false
        }

        scope.launch {
            try {
                onStatusChanged?.invoke("Listening for Bluetooth SPP connections...")
                serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
                Log.i(TAG, "Bluetooth RFCOMM Server listening...")

                clientSocket = serverSocket?.accept()
                outputStream = clientSocket?.outputStream
                isBluetoothConnected = true
                val deviceName = clientSocket?.remoteDevice?.name ?: "Remote Device"
                onStatusChanged?.invoke("Connected via Bluetooth to $deviceName")
                Log.i(TAG, "Bluetooth RFCOMM Connected to $deviceName")
            } catch (e: Exception) {
                Log.e(TAG, "Error in Bluetooth RFCOMM server", e)
                isBluetoothConnected = false
                onStatusChanged?.invoke("Bluetooth Server Error")
            }
        }
        return true
    }

    fun sendAudioFrame(buffer: ByteArray, length: Int) {
        if (!isBluetoothConnected || outputStream == null) return

        scope.launch {
            try {
                outputStream?.write(buffer, 0, length)
                outputStream?.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Error writing to Bluetooth stream", e)
            }
        }
    }

    @Suppress("DEPRECATION")
    fun toggleBluetoothSco(enable: Boolean): Boolean {
        return try {
            if (enable) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
                isScoActive = true
                onStatusChanged?.invoke("Bluetooth SCO Headset Mic Active")
                Log.i(TAG, "Bluetooth SCO started")
            } else {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
                audioManager.mode = AudioManager.MODE_NORMAL
                isScoActive = false
                onStatusChanged?.invoke("Bluetooth SCO Disconnected")
                Log.i(TAG, "Bluetooth SCO stopped")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle Bluetooth SCO", e)
            false
        }
    }

    fun stopBluetooth() {
        toggleBluetoothSco(false)
        try {
            outputStream?.close()
            clientSocket?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing Bluetooth sockets", e)
        } finally {
            outputStream = null
            clientSocket = null
            serverSocket = null
            isBluetoothConnected = false
        }
    }
}
