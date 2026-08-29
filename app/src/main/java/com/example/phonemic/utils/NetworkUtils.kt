package com.example.phonemic.utils

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import kotlin.random.Random

object NetworkUtils {

    /**
     * Retrieves the IPv4 address of the device on the Wi-Fi or Local Network.
     */
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress
                        if (ip != null && !ip.startsWith("127.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * Generates a random 4-digit Security PIN (e.g. "4829").
     */
    fun generateSecurityPin(): String {
        val pinInt = Random.nextInt(1000, 9999)
        return pinInt.toString()
    }

    /**
     * Finds an available open port within a specified range.
     */
    fun findRandomFreePort(minPort: Int, maxPort: Int): Int {
        for (i in 0..50) {
            val candidatePort = Random.nextInt(minPort, maxPort)
            if (isPortAvailable(candidatePort)) {
                return candidatePort
            }
        }
        return minPort // Fallback
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Generates a QR Code Bitmap for the given content string.
     */
    fun generateQrCode(content: String, width: Int = 512, height: Int = 512): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
