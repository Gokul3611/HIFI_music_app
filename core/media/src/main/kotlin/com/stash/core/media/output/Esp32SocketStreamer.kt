package com.stash.core.media.output

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance, zero-overhead TCP audio streamer for ESP32 Hi-Fi.
 *
 * Transmits uncompressed 24-bit / 96 kHz PCM audio frames directly from
 * Stash's audio decoder to the ESP32-WROOM-32 over a local Wi-Fi Hotspot.
 * 
 * Bypasses Android's AudioTrack, AudioFlinger, and Bluetooth compression.
 */
object Esp32SocketStreamer {
    private const val TAG = "Esp32SocketStreamer"
    
    // Default ESP32 hotspot gateway address and port
    var espHost: String = "192.168.43.1"
    var espPort: Int = 8888

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private val isConnecting = AtomicBoolean(false)
    val isConnected = AtomicBoolean(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Connects to the ESP32 audio receiver asynchronously.
     */
    fun connect(host: String = espHost, port: Int = espPort) {
        if (isConnected.get() || isConnecting.get()) return

        isConnecting.set(true)
        scope.launch {
            try {
                Log.i(TAG, "Connecting to ESP32 Hi-Fi Audio Server at $host:$port...")
                val sock = Socket()
                sock.tcpNoDelay = true // Disable Nagle's algorithm for low-latency audio
                sock.sendBufferSize = 64 * 1024 // 64 KB send buffer
                sock.connect(InetSocketAddress(host, port), 3000)

                val os = sock.getOutputStream()

                // Send 16-byte Hi-Fi Handshake Header:
                // [4B Magic: 'HIFI'] [4B SampleRate: 96000] [2B BitDepth: 24] [2B Channels: 2] [4B Reserved: 0]
                val header = ByteBuffer.allocate(16)
                header.put("HIFI".toByteArray())
                header.putInt(96000)
                header.putShort(24.toShort())
                header.putShort(2.toShort())
                header.putInt(0)
                os.write(header.array())
                os.flush()

                socket = sock
                outputStream = os
                isConnected.set(true)
                Log.i(TAG, "Connected to ESP32! Bit-perfect 24/96 stream active.")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to connect to ESP32 ($host:$port): ${e.message}")
                close()
            } finally {
                isConnecting.set(false)
            }
        }
    }

    /**
     * Sends raw PCM byte buffer to ESP32 over TCP.
     */
    fun write(buffer: ByteBuffer) {
        if (!isConnected.get()) {
            connect()
            return
        }

        try {
            val os = outputStream ?: return
            val length = buffer.remaining()
            if (length == 0) return

            // Write bytes directly without extra memory copy if backing array exists
            if (buffer.hasArray()) {
                val offset = buffer.arrayOffset() + buffer.position()
                os.write(buffer.array(), offset, length)
                buffer.position(buffer.position() + length)
            } else {
                val temp = ByteArray(length)
                buffer.get(temp)
                os.write(temp)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Socket write error: ${e.message}. Reconnecting...")
            close()
        }
    }

    fun close() {
        isConnected.set(false)
        try {
            outputStream?.close()
        } catch (_: Exception) {}
        try {
            socket?.close()
        } catch (_: Exception) {}
        outputStream = null
        socket = null
    }
}

