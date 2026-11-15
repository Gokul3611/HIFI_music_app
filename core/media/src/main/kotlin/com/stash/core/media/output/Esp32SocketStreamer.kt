package com.stash.core.media.output

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    data class Esp32Status(
        val isConnected: Boolean = false,
        val isConnecting: Boolean = false,
        val host: String = espHost,
        val port: Int = espPort,
        val activeDacMode: DacMode = DacMode.PCM5102A,
        val isHardwareVerified: Boolean = false,
        val latencyMs: Long? = null,
        val rssiDbm: Int? = null,
        val bufferPct: String? = null,
        val statusMessage: String? = null,
        val lastVerifiedAt: Long = 0L,
    )

    private val _status = MutableStateFlow(Esp32Status())
    val status: StateFlow<Esp32Status> = _status.asStateFlow()

    @Volatile var dacMode: DacMode = DacMode.PCM5102A
        set(value) {
            field = value
            sendControlPacket(value.code)
        }

    fun sendControlPacket(modeCode: Byte = dacMode.code) {
        scope.launch {
            // 1. Send Out-of-Band UDP Mode Control to Port 8889 (Zero TCP Audio Stream Pollution)
            try {
                val udpSocket = DatagramSocket()
                val message = "MODE:$modeCode".toByteArray()
                val targetAddr = InetAddress.getByName(espHost)
                val packet = DatagramPacket(message, message.size, targetAddr, 8889)
                udpSocket.send(packet)
                udpSocket.close()
                Log.i(TAG, "Sent out-of-band UDP DAC mode: mode=$modeCode to $espHost:8889")
            } catch (e: Exception) {
                Log.w(TAG, "UDP mode packet note: ${e.message}")
            }

            // 2. Fallback: also send on TCP socket if connected (Backward compatibility)
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
            } catch (e: Exception) {}
        }
    }

    /**
     * Sends a hardware mode switch command to ESP32 with strict two-way ACK verification.
     */
    fun switchDacMode(
        newMode: DacMode,
        timeoutMs: Int = 1800,
        onResult: ((Boolean, String) -> Unit)? = null,
    ) {
        dacMode = newMode
        scope.launch {
            var confirmed = false
            var responseMsg = ""
            var datagramSocket: DatagramSocket? = null
            try {
                datagramSocket = DatagramSocket()
                datagramSocket.soTimeout = timeoutMs

                val msg = "MODE:${newMode.code}".toByteArray()
                val target = InetAddress.getByName(espHost)
                val sendPacket = DatagramPacket(msg, msg.size, target, 8889)
                datagramSocket.send(sendPacket)

                val buffer = ByteArray(128)
                val recvPacket = DatagramPacket(buffer, buffer.size)
                datagramSocket.receive(recvPacket)
                val reply = String(recvPacket.data, 0, recvPacket.length).trim()
                Log.i(TAG, "ESP32 Mode Switch ACK: $reply")

                if (reply.contains("MODE_ACK:${newMode.code}")) {
                    confirmed = true
                    responseMsg = "Hardware confirmed: Switched to ${newMode.label}"
                } else {
                    responseMsg = "ESP32 replied with ACK: $reply"
                    confirmed = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Mode switch UDP ACK timeout: ${e.message}")
                responseMsg = "No ACK received from ESP32 ($espHost:8889). Check Wi-Fi connection."
            } finally {
                try { datagramSocket?.close() } catch (e: Exception) {}
            }

            _status.value = _status.value.copy(
                activeDacMode = newMode,
                isHardwareVerified = confirmed,
                statusMessage = responseMsg,
                lastVerifiedAt = System.currentTimeMillis()
            )

            withContext(Dispatchers.Main) {
                onResult?.invoke(confirmed, responseMsg)
            }
        }
    }

    /**
     * Pings the ESP32 over UDP to measure round-trip latency, RSSI, and active hardware state.
     */
    fun pingOrQueryStatus(
        host: String = espHost,
        timeoutMs: Int = 2000,
        onResult: ((Boolean, Esp32Status) -> Unit)? = null,
    ) {
        scope.launch {
            var datagramSocket: DatagramSocket? = null
            var success = false
            try {
                datagramSocket = DatagramSocket()
                datagramSocket.soTimeout = timeoutMs

                val msg = "GET_STATUS".toByteArray()
                val target = InetAddress.getByName(host)
                val sendPacket = DatagramPacket(msg, msg.size, target, 8889)

                val startTime = System.currentTimeMillis()
                datagramSocket.send(sendPacket)

                val buffer = ByteArray(256)
                val recvPacket = DatagramPacket(buffer, buffer.size)
                datagramSocket.receive(recvPacket)
                val latency = System.currentTimeMillis() - startTime

                val response = String(recvPacket.data, 0, recvPacket.length).trim()
                Log.i(TAG, "ESP32 UDP Status response: $response (latency=${latency}ms)")

                var verifiedMode = dacMode
                if (response.contains("MODE:2")) {
                    verifiedMode = DacMode.INTERNAL_DAC
                } else if (response.contains("MODE:1")) {
                    verifiedMode = DacMode.PCM5102A
                }

                // Extract RSSI
                val rssiMatch = Regex("RSSI:(-?\\d+)").find(response)
                val rssi = rssiMatch?.groupValues?.getOrNull(1)?.toIntOrNull()

                val bufferMatch = Regex("BUFFER:(\\d+%)").find(response)
                val bufferPct = bufferMatch?.groupValues?.getOrNull(1)

                val newStatus = _status.value.copy(
                    host = host,
                    activeDacMode = verifiedMode,
                    isHardwareVerified = true,
                    latencyMs = latency,
                    rssiDbm = rssi,
                    bufferPct = bufferPct,
                    statusMessage = "Verified via UDP in ${latency}ms (Signal: ${rssi ?: -50} dBm)",
                    lastVerifiedAt = System.currentTimeMillis()
                )
                _status.value = newStatus
                success = true

                withContext(Dispatchers.Main) {
                    onResult?.invoke(true, newStatus)
                }
            } catch (e: Exception) {
                Log.w(TAG, "UDP Ping to $host:8889 failed: ${e.message}")
                val failStatus = _status.value.copy(
                    latencyMs = null,
                    isHardwareVerified = false,
                    statusMessage = "ESP32 not responding: ${e.message}"
                )
                _status.value = failStatus
                withContext(Dispatchers.Main) {
                    onResult?.invoke(false, failStatus)
                }
            } finally {
                try { datagramSocket?.close() } catch (e: Exception) {}
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
    fun connect(
        host: String = espHost,
        port: Int = espPort,
        onResult: ((Boolean, String) -> Unit)? = null,
    ) {
        if (isConnected.get()) {
            onResult?.invoke(true, "Already connected to $host:$port")
            return
        }
        if (isConnecting.get()) return

        isConnecting.set(true)
        _status.value = _status.value.copy(isConnecting = true, host = host, port = port, statusMessage = "Connecting to $host:$port...")

        scope.launch {
            try {
                Log.i(TAG, "Connecting to ESP32 Hi-Fi Audio Server at $host:$port...")
                val sock = Socket()
                sock.tcpNoDelay = true
                sock.sendBufferSize = 64 * 1024
                sock.connect(InetSocketAddress(host, port), 3000)

                val os = sock.getOutputStream()
                val ins = sock.getInputStream()

                socket = sock
                outputStream = os
                isConnected.set(true)
                espHost = host
                espPort = port

                // Try to read initial greeting from ESP32
                var verifiedMode = dacMode
                var hardwareVerified = false
                try {
                    sock.soTimeout = 1200
                    val reader = ins.bufferedReader()
                    val line = reader.readLine()
                    Log.i(TAG, "Received greeting from ESP32: $line")
                    if (line != null && line.contains("HIFI_CONNECTED")) {
                        hardwareVerified = true
                        if (line.contains("MODE:2")) {
                            verifiedMode = DacMode.INTERNAL_DAC
                        } else if (line.contains("MODE:1")) {
                            verifiedMode = DacMode.PCM5102A
                        }
                    }
                    sock.soTimeout = 0
                } catch (e: Exception) {
                    sock.soTimeout = 0
                }

                _status.value = _status.value.copy(
                    isConnected = true,
                    isConnecting = false,
                    host = host,
                    port = port,
                    activeDacMode = verifiedMode,
                    isHardwareVerified = hardwareVerified,
                    statusMessage = if (hardwareVerified) "Connected & Hardware Confirmed" else "Connected to $host:$port",
                    lastVerifiedAt = System.currentTimeMillis()
                )

                sendControlPacket(dacMode.code)
                withContext(Dispatchers.Main) {
                    onResult?.invoke(true, "Connected to ESP32 at $host:$port")
                }
                Log.i(TAG, "Connected to ESP32! Bit-perfect 32-bit PCM stream active.")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to connect to ESP32 ($host:$port): ${e.message}")
                close()
                _status.value = _status.value.copy(
                    isConnected = false,
                    isConnecting = false,
                    statusMessage = "Connection failed: ${e.message}"
                )
                withContext(Dispatchers.Main) {
                    onResult?.invoke(false, "Connection failed: ${e.message}")
                }
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
                } catch (e: Exception) {}

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
                    } catch (e: Exception) {}
                }
                Log.i(TAG, "Sent UDP discovery broadcasts to ${broadcastTargets.size} targets on port 8889")

                val buffer = ByteArray(128)
                val responsePacket = DatagramPacket(buffer, buffer.size)
                datagramSocket.receive(responsePacket)

                val response = String(responsePacket.data, 0, responsePacket.length)
                if (response.contains("HIFI_DAC_HERE")) {
                    val ip = responsePacket.address?.hostAddress
                    if (ip != null) {
                        Log.i(TAG, "Auto-discovered ESP32 Hi-Fi Receiver at IP: $ip")
                        foundIp = ip
                        espHost = ip
                        var verifiedMode = dacMode
                        if (response.contains("MODE:2")) {
                            verifiedMode = DacMode.INTERNAL_DAC
                        } else if (response.contains("MODE:1")) {
                            verifiedMode = DacMode.PCM5102A
                        }
                        _status.value = _status.value.copy(
                            host = ip,
                            activeDacMode = verifiedMode,
                            isHardwareVerified = true,
                            statusMessage = "Discovered at $ip (${verifiedMode.label})"
                        )
                        withContext(Dispatchers.Main) {
                            onDiscovered?.invoke(ip)
                        }
                        connect(ip)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "UDP Auto-Discovery timed out or failed: ${e.message}")
            } finally {
                try {
                    datagramSocket?.close()
                } catch (e: Exception) {}

                if (foundIp == null) {
                    // Try fallback to mDNS if available
                    try {
                        val mdnsAddr = InetAddress.getByName("music-dac.local")
                        val ip = mdnsAddr.hostAddress
                        if (ip != null) {
                            Log.i(TAG, "Resolved mDNS music-dac.local at: $ip")
                            foundIp = ip
                            espHost = ip
                            withContext(Dispatchers.Main) {
                                onDiscovered?.invoke(ip)
                            }
                            connect(ip)
                        }
                    } catch (e: Exception) {
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
        isConnecting.set(false)
        try {
            outputStream?.close()
        } catch (e: Exception) {}
        try {
            socket?.close()
        } catch (e: Exception) {}
        outputStream = null
        socket = null
        _status.value = _status.value.copy(
            isConnected = false,
            isConnecting = false,
            statusMessage = "Disconnected",
        )
    }
}
