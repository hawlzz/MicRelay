package com.example.phonemic.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UdpStreamer(
    val udpPort: Int,
    @Volatile var securityPin: String
) {

    companion object {
        private const val TAG = "UdpStreamer"
    }

    private var socket: DatagramSocket? = null
    private val authorizedClients = mutableSetOf<InetSocketAddress>()

    @Volatile
    var isStreaming: Boolean = false
        private set

    private var sequenceNumber: Int = 0
    private val scope = CoroutineScope(Dispatchers.IO)
    private var authListenerJob: Job? = null

    fun startStreaming(): Boolean {
        return try {
            socket = DatagramSocket(udpPort)
            sequenceNumber = 0
            isStreaming = true
            authorizedClients.clear()
            startAuthHandshakeListener()
            Log.i(TAG, "UDP Streaming socket listening on dynamic port $udpPort")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start UDP socket on port $udpPort", e)
            stopStreaming()
            false
        }
    }

    private fun startAuthHandshakeListener() {
        authListenerJob = scope.launch {
            val rxBuffer = ByteArray(512)
            while (isStreaming && socket != null && !socket!!.isClosed) {
                try {
                    val packet = DatagramPacket(rxBuffer, rxBuffer.size)
                    socket?.receive(packet)

                    val message = String(packet.data, 0, packet.length).trim()
                    val clientAddress = InetSocketAddress(packet.address, packet.port)

                    Log.d(TAG, "Received UDP Handshake: $message from $clientAddress")

                    if (message.startsWith("AUTH:")) {
                        val clientPin = message.substringAfter("AUTH:").trim()
                        if (clientPin == securityPin) {
                            synchronized(authorizedClients) {
                                authorizedClients.add(clientAddress)
                            }
                            sendResponse(clientAddress, "AUTH_OK")
                            Log.i(TAG, "UDP Client Authorized: $clientAddress")
                        } else {
                            sendResponse(clientAddress, "AUTH_DENIED")
                            Log.w(TAG, "UDP Client Auth Denied: $clientAddress (PIN: $clientPin)")
                        }
                    }
                } catch (e: Exception) {
                    if (isStreaming) {
                        Log.w(TAG, "Error in UDP handshake listener", e)
                    }
                }
            }
        }
    }

    private fun sendResponse(clientAddress: InetSocketAddress, responseText: String) {
        try {
            val bytes = responseText.toByteArray()
            val packet = DatagramPacket(bytes, bytes.size, clientAddress.address, clientAddress.port)
            socket?.send(packet)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send UDP handshake response", e)
        }
    }

    fun sendAudioFrame(buffer: ByteArray, length: Int) {
        if (!isStreaming || socket == null) return

        val activeClients = synchronized(authorizedClients) { authorizedClients.toList() }
        if (activeClients.isEmpty()) return

        scope.launch {
            try {
                // Header: 4-byte Sequence Number + Payload
                val packetSize = 4 + length
                val packetData = ByteArray(packetSize)

                ByteBuffer.wrap(packetData).order(ByteOrder.BIG_ENDIAN).apply {
                    putInt(sequenceNumber++)
                    put(buffer, 0, length)
                }

                for (client in activeClients) {
                    try {
                        val packet = DatagramPacket(packetData, packetSize, client.address, client.port)
                        socket?.send(packet)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending UDP audio packet to $client", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error packaging UDP audio frame", e)
            }
        }
    }

    fun stopStreaming() {
        isStreaming = false
        authListenerJob?.cancel()
        authListenerJob = null

        try {
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing UDP socket", e)
        } finally {
            socket = null
            synchronized(authorizedClients) { authorizedClients.clear() }
        }
        Log.i(TAG, "UDP Streaming stopped")
    }
}
