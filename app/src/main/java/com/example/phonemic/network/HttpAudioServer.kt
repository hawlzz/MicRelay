package com.example.phonemic.network

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HttpAudioServer(
    port: Int = 8080,
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
                    // Client disconnected or pipe broken
                    try { os.close() } catch (_: Exception) {}
                    iterator.remove()
                }
            }
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "HTTP Request: $uri")

        return when {
            uri == "/" || uri == "/index.html" -> serveWebPlayer()
            uri.startsWith("/stream") -> serveAudioStream()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    private fun serveWebPlayer(): Response {
        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>PhoneMic Wireless Live Receiver</title>
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
                    <h1>🎙️ PhoneMic Wireless</h1>
                    <p>Live Audio Streaming Receiver</p>

                    <button class="btn" id="playBtn" onclick="toggleAudio()">▶ Connect & Play Live Stream</button>
                    <div class="status" id="statusText">Connected to Live Microphone Stream</div>

                    <audio id="audioPlayer" controls></audio>

                    <div class="visualizer" id="viz">
                        <!-- Bars generated by JS -->
                    </div>
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

                    function toggleAudio() {
                        if (!isPlaying) {
                            audio.src = "/stream.wav?" + Date.now();
                            audio.play().then(() => {
                                isPlaying = true;
                                btn.innerText = "⏸ Pause Receiver";
                                btn.style.background = "#e11d48";
                                status.style.display = "block";
                                animateVisualizer();
                            }).catch(err => {
                                alert("Failed to start audio playback: " + err);
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

        // Write WAV header into piped stream before PCM data
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

        // RIFF/WAVE header with dummy high size for continuous streaming
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(0x7fffffff) // ChunkSize max placeholder
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16) // Subchunk1Size (16 for PCM)
            putShort(1.toShort()) // AudioFormat (1 for PCM)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray())
            putInt(0x7fffffff) // Subchunk2Size max placeholder
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
