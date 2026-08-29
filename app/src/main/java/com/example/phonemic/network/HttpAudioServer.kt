package com.example.phonemic.network

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HttpAudioServer(
    port: Int,
    @Volatile var securityPin: String,
    private val sampleRate: Int = 44100,
    private val channels: Int = 1
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "HttpAudioServer"
    }

    private val audioListeners = mutableListOf<PipedOutputStream>()

    fun onAudioDataReceived(buffer: ByteArray, length: Int) {
        synchronized(audioListeners) {
            val iterator = audioListeners.iterator()
            while (iterator.hasNext()) {
                val os = iterator.next()
                try {
                    os.write(buffer, 0, length)
                    os.flush()
                } catch (e: Exception) {
                    try { os.close() } catch (_: Exception) {}
                    iterator.remove()
                }
            }
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "HTTP Request: $uri from ${session.remoteIpAddress}")

        return when {
            uri == "/" || uri == "/index.html" -> serveWebPlayer(session)
            uri.startsWith("/stream") -> {
                // PIN Security Check
                val requestPin = session.parms["pin"] ?: session.headers["x-pin"]
                if (requestPin != securityPin) {
                    Log.w(TAG, "Unauthorized stream access attempt from ${session.remoteIpAddress} (PIN: $requestPin)")
                    val unauthJson = """{"status":"error","message":"HTTP 401 Unauthorized: Invalid or missing Security PIN"}"""
                    newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", unauthJson)
                } else {
                    serveAudioStream()
                }
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    private fun serveWebPlayer(session: IHTTPSession): Response {
        val requestPin = session.parms["pin"] ?: ""

        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>MicRelay Live Receiver</title>
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
                        background: #0f172a;
                        color: #f8fafc;
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        justify-content: center;
                        min-height: 100vh;
                        margin: 0;
                        padding: 20px;
                    }
                    .card {
                        background: #1e293b;
                        padding: 32px;
                        border-radius: 24px;
                        box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.5);
                        text-align: center;
                        max-width: 440px;
                        width: 100%;
                        border: 1px solid #334155;
                    }
                    h1 { margin-top: 0; font-size: 24px; color: #38bdf8; }
                    p { color: #94a3b8; font-size: 14px; margin-bottom: 24px; }
                    .pin-box {
                        margin-bottom: 20px;
                        text-align: left;
                    }
                    label { display: block; font-size: 12px; color: #94a3b8; margin-bottom: 6px; font-weight: 600; }
                    input[type="text"] {
                        width: 100%;
                        padding: 12px 16px;
                        border-radius: 10px;
                        border: 1px solid #475569;
                        background: #0f172a;
                        color: #38bdf8;
                        font-size: 18px;
                        font-weight: bold;
                        letter-spacing: 2px;
                        box-sizing: border-box;
                        text-align: center;
                    }
                    .btn {
                        background: #0284c7;
                        color: white;
                        border: none;
                        padding: 14px 28px;
                        font-size: 16px;
                        font-weight: 600;
                        border-radius: 12px;
                        cursor: pointer;
                        transition: all 0.2s;
                        width: 100%;
                        box-sizing: border-box;
                    }
                    .btn:hover { background: #0369a1; transform: translateY(-1px); }
                    .btn:active { transform: translateY(0); }
                    audio { margin-top: 24px; width: 100%; display: none; }
                    .status {
                        margin-top: 16px;
                        font-size: 13px;
                        color: #4ade80;
                        display: none;
                        font-weight: 500;
                    }
                    .error {
                        margin-top: 16px;
                        font-size: 13px;
                        color: #ef4444;
                        display: none;
                        font-weight: 500;
                    }
                    .visualizer {
                        width: 100%;
                        height: 60px;
                        background: #0f172a;
                        border-radius: 8px;
                        margin-top: 20px;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        overflow: hidden;
                    }
                    .bar {
                        width: 4px;
                        height: 20%;
                        background: #38bdf8;
                        margin: 0 2px;
                        border-radius: 2px;
                        transition: height 0.05s ease;
                    }
                </style>
            </head>
            <body>
                <div class="card">
                    <h1>🎙️ MicRelay Wireless</h1>
                    <p>Protected Audio Stream Receiver</p>

                    <div class="pin-box">
                        <label for="pinInput">SECURITY PIN</label>
                        <input type="text" id="pinInput" placeholder="Enter 4-digit PIN" maxlength="6" value="$requestPin" />
                    </div>

                    <button class="btn" id="playBtn" onclick="toggleAudio()">▶ Connect & Play Live Stream</button>
                    <div class="status" id="statusText">Connected to Encrypted Live Stream</div>
                    <div class="error" id="errorText">Access Denied: Invalid Security PIN</div>

                    <audio id="audioPlayer" controls></audio>

                    <div class="visualizer" id="viz"></div>
                </div>

                <script>
                    const viz = document.getElementById('viz');
                    const bars = [];
                    for(let i=0; i<20; i++){
                        const bar = document.createElement('div');
                        bar.className = 'bar';
                        viz.appendChild(bar);
                        bars.push(bar);
                    }

                    let isPlaying = false;
                    const audio = document.getElementById('audioPlayer');
                    const btn = document.getElementById('playBtn');
                    const status = document.getElementById('statusText');
                    const error = document.getElementById('errorText');
                    const pinInput = document.getElementById('pinInput');

                    function toggleAudio() {
                        const pin = pinInput.value.trim();
                        if (!pin) {
                            alert("Please enter the 4-digit Security PIN shown on the phone screen.");
                            return;
                        }

                        if (!isPlaying) {
                            error.style.display = "none";
                            const streamUrl = "/stream.wav?pin=" + encodeURIComponent(pin) + "&t=" + Date.now();
                            audio.src = streamUrl;
                            audio.play().then(() => {
                                isPlaying = true;
                                btn.innerText = "⏸ Pause Receiver";
                                btn.style.background = "#e11d48";
                                status.style.display = "block";
                                animateVisualizer();
                            }).catch(err => {
                                isPlaying = false;
                                error.innerText = "Connection Failed: Invalid PIN or Stream Offline";
                                error.style.display = "block";
                                status.style.display = "none";
                            });
                        } else {
                            audio.pause();
                            audio.src = "";
                            isPlaying = false;
                            btn.innerText = "▶ Connect & Play Live Stream";
                            btn.style.background = "#0284c7";
                            status.style.display = "none";
                        }
                    }

                    function animateVisualizer() {
                        if (!isPlaying) return;
                        bars.forEach(b => {
                            const h = Math.floor(Math.random() * 80) + 10;
                            b.style.height = h + '%';
                        });
                        setTimeout(animateVisualizer, 80);
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        return newFixedLengthResponse(Response.Status.OK, MIME_HTML, html)
    }

    private fun serveAudioStream(): Response {
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, 65536)

        synchronized(audioListeners) {
            audioListeners.add(pipedOut)
        }

        Thread {
            try {
                val header = createWavHeader(sampleRate, channels, 16)
                pipedOut.write(header)
                pipedOut.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Error writing WAV header", e)
            }
        }.start()

        val response = newChunkedResponse(Response.Status.OK, "audio/wav", pipedIn)
        response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        response.addHeader("Pragma", "no-cache")
        response.addHeader("Expires", "0")
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    private fun createWavHeader(sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val header = ByteArray(44)
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(0x7fffffff)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1.toShort())
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray())
            putInt(0x7fffffff)
        }
        return header
    }

    override fun closeAllConnections() {
        super.closeAllConnections()
        synchronized(audioListeners) {
            for (os in audioListeners) {
                try { os.close() } catch (_: Exception) {}
            }
            audioListeners.clear()
        }
    }
}
