package com.stash.core.media.output

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance, zero-overhead TCP audio streamer for ESP32 Hi-Fi.
 *
 * Converts Media3 decoded audio (Float, Int16, Int24, Int32) into clean,
 * little-endian 32-bit signed integer PCM frames expected by the ESP32 I2S
 * DMA engine and PCM5102A DAC, and streams over TCP.
 */
object Esp32SocketStreamer {
    private const val TAG = "Esp32SocketStreamer"

    // Default ESP32 hotspot IP address and port
    @Volatile var espHost: String = "192.168.43.1"
    @Volatile var espPort: Int = 8888

    enum class DacMode(val code: Byte, val label: String) {
        PCM5102A(1.toByte(), "External PCM5102A (24-bit/96kHz)"),
        INTERNAL_DAC(2.toByte(), "ESP32 Built-in DAC (GPIO 25/26)"),
    }

    @Volatile var dacMode: DacMode = DacMode.PCM5102A

    fun setDacMode(mode: DacMode) {
        dacMode = mode
        sendControlPacket(mode.code)
    }

    fun sendControlPacket(modeCode: Byte = dacMode.code) {
        scope.launch {
            try {
                val os = outputStream ?: return@launch
                val packet = byteArrayOf(
                    'H'.code.toByte(),
                    'I'.code.toByte(),
                    'F'.code.toByte(),
                    'I'.code.toByte(),
                    modeCode,
                    0.toByte(),
                    0.toByte(),
                    0.toByte()
                )
                os.write(packet)
                os.flush()
                Log.i(TAG, "Sent DAC mode control packet: mode=$modeCode")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send DAC mode packet: ${e.message}")
            }
        }
    }

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private val isConnecting = AtomicBoolean(false)
    val isConnected = AtomicBoolean(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Reusable conversion buffer on the audio thread (zero GC churn)
    private var outBytes = ByteArray(16384)

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
                sock.tcpNoDelay = true
                sock.sendBufferSize = 64 * 1024
                sock.connect(InetSocketAddress(host, port), 3000)

                val os = sock.getOutputStream()

                socket = sock
                outputStream = os
                isConnected.set(true)
                sendControlPacket(dacMode.code)
                Log.i(TAG, "Connected to ESP32! Bit-perfect 32-bit PCM stream active.")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to connect to ESP32 ($host:$port): ${e.message}")
                close()
            } finally {
                isConnecting.set(false)
            }
        }
    }

    /**
     * Broadcasts UDP discovery packet on port 8889 to find the ESP32 IP on the network.
     * When ESP32 responds with "HIFI_DAC_HERE", updates espHost and triggers connection.
     */
    fun discoverAndConnect(
        onDiscovered: ((String) -> Unit)? = null,
        onFailed: (() -> Unit)? = null,
    ) {
        scope.launch {
            var foundIp: String? = null
            var datagramSocket: DatagramSocket? = null
            try {
                datagramSocket = DatagramSocket()
                datagramSocket.broadcast = true
                datagramSocket.soTimeout = 2500 // 2.5 seconds discovery window

                val message = "HIFI_DISCOVER".toByteArray()

                // Collect broadcast addresses across all network interfaces
                val broadcastTargets = mutableSetOf<InetAddress>()
                try {
                    broadcastTargets.add(InetAddress.getByName("255.255.255.255"))
                } catch (_: Exception) {}

                try {
                    val interfaces = NetworkInterface.getNetworkInterfaces()
                    while (interfaces.hasMoreElements()) {
                        val netIf = interfaces.nextElement()
                        if (netIf.isLoopback || !netIf.isUp) continue
                        for (addr in netIf.interfaceAddresses) {
                            val bcast = addr.broadcast
                            if (bcast != null) {
                                broadcastTargets.add(bcast)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Interface enumeration note: ${e.message}")
                }

                for (target in broadcastTargets) {
                    try {
                        val packet = DatagramPacket(message, message.size, target, 8889)
                        datagramSocket.send(packet)
                    } catch (_: Exception) {}
                }
                Log.i(TAG, "Sent UDP discovery broadcasts to ${broadcastTargets.size} targets on port 8889")

                val buffer = ByteArray(128)
                val responsePacket = DatagramPacket(buffer, buffer.size)
                datagramSocket.receive(responsePacket)

                val response = String(responsePacket.data, 0, responsePacket.length)
                if (response.contains("HIFI_DAC_HERE")) {
                    val ip = responsePacket.address.hostAddress
                    Log.i(TAG, "Auto-discovered ESP32 Hi-Fi Receiver at IP: $ip")
                    foundIp = ip
                    espHost = ip
                    withContext(Dispatchers.Main) {
                        onDiscovered?.invoke(ip)
                    }
                    connect(ip)
                }
            } catch (e: Exception) {
                Log.w(TAG, "UDP Auto-Discovery timed out or failed: ${e.message}")
            } finally {
                try {
                    datagramSocket?.close()
                } catch (_: Exception) {}

                if (foundIp == null) {
                    // Try fallback to mDNS if available
                    try {
                        val mdnsAddr = InetAddress.getByName("music-dac.local")
                        val ip = mdnsAddr.hostAddress
                        Log.i(TAG, "Resolved mDNS music-dac.local at: $ip")
                        foundIp = ip
                        espHost = ip
                        withContext(Dispatchers.Main) {
                            onDiscovered?.invoke(ip)
                        }
                        connect(ip)
                    } catch (_: Exception) {
                        withContext(Dispatchers.Main) {
                            onFailed?.invoke()
                        }
                    }
                }
            }
        }
    }

    /**
     * Converts decoded Media3 ByteBuffer into 32-bit signed little-endian PCM
     * and writes directly to the ESP32 socket.
     */
    fun writePcm(buffer: ByteBuffer, inFormat: SampleFormat) {
        if (!isConnected.get()) {
            connect()
            return
        }

        try {
            val os = outputStream ?: return
            val view = buffer.duplicate().order(ByteOrder.nativeOrder())
            val remaining = view.remaining()
            if (remaining == 0) return

            val byteCount: Int
            when (inFormat) {
                SampleFormat.INT32 -> {
                    byteCount = remaining
                    ensureBuffer(byteCount)
                    view.get(outBytes, 0, byteCount)
                }
                SampleFormat.FLOAT -> {
                    val sampleCount = remaining / 4
                    byteCount = sampleCount * 4
                    ensureBuffer(byteCount)
                    val floats = view.asFloatBuffer()
                    var outIdx = 0
                    for (i in 0 until sampleCount) {
                        val s = floats.get(i).coerceIn(-1.0f, 1.0f)
                        val v = (s * 2147483647.0).toInt()
                        outBytes[outIdx++] = (v and 0xFF).toByte()
                        outBytes[outIdx++] = ((v ushr 8) and 0xFF).toByte()
                        outBytes[outIdx++] = ((v ushr 16) and 0xFF).toByte()
                        outBytes[outIdx++] = ((v ushr 24) and 0xFF).toByte()
                    }
                }
                SampleFormat.INT24 -> {
                    val sampleCount = remaining / 3
                    byteCount = sampleCount * 4
                    ensureBuffer(byteCount)
                    var pos = view.position()
                    var outIdx = 0
                    for (i in 0 until sampleCount) {
                        outBytes[outIdx++] = 0.toByte() // Pad LSB with 0x00 for 32-bit slot
                        outBytes[outIdx++] = view.get(pos)
                        outBytes[outIdx++] = view.get(pos + 1)
                        outBytes[outIdx++] = view.get(pos + 2)
                        pos += 3
                    }
                }
                SampleFormat.INT16 -> {
                    val sampleCount = remaining / 2
                    byteCount = sampleCount * 4
                    ensureBuffer(byteCount)
                    val shorts = view.asShortBuffer()
                    var outIdx = 0
                    for (i in 0 until sampleCount) {
                        val v = shorts.get(i).toInt() shl 16
                        outBytes[outIdx++] = 0.toByte()
                        outBytes[outIdx++] = 0.toByte()
                        outBytes[outIdx++] = ((v ushr 16) and 0xFF).toByte()
                        outBytes[outIdx++] = ((v ushr 24) and 0xFF).toByte()
                    }
                }
            }

            os.write(outBytes, 0, byteCount)
        } catch (e: Exception) {
            Log.e(TAG, "Socket write error: ${e.message}. Reconnecting...")
            close()
        }
    }

    private fun ensureBuffer(required: Int) {
        if (outBytes.size < required) {
            outBytes = ByteArray(required)
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
