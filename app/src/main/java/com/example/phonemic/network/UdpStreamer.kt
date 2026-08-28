package com.example.phonemic.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UdpStreamer {

    companion object {
        private const val TAG = "UdpStreamer"
    }

    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private var targetPort: Int = 50005

    @Volatile
    var isStreaming: Boolean = false
        private set

    private var sequenceNumber: Int = 0
    private val scope = CoroutineScope(Dispatchers.IO)

    fun startStreaming(targetIp: String, port: Int = 50005): Boolean {
        return try {
            targetAddress = InetAddress.getByName(targetIp)
            targetPort = port
            socket = DatagramSocket()
            sequenceNumber = 0
            isStreaming = true
            Log.i(TAG, "UDP Streaming target set to $targetIp:$port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start UDP socket", e)
            stopStreaming()
            false
        }
    }

    fun sendAudioFrame(buffer: ByteArray, length: Int) {
        if (!isStreaming || socket == null || targetAddress == null) return

        scope.launch {
            try {
                // Header: 4-byte Sequence Number + Payload
                val packetSize = 4 + length
                val packetData = ByteArray(packetSize)

                ByteBuffer.wrap(packetData).order(ByteOrder.BIG_ENDIAN).apply {
                    putInt(sequenceNumber++)
                    put(buffer, 0, length)
                }

                val packet = DatagramPacket(packetData, packetSize, targetAddress, targetPort)
                socket?.send(packet)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending UDP packet", e)
            }
        }
    }

    fun stopStreaming() {
        isStreaming = false
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing UDP socket", e)
        } finally {
            socket = null
            targetAddress = null
        }
        Log.i(TAG, "UDP Streaming stopped")
    }
}
