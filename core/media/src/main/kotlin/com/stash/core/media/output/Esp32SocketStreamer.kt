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
import java.io.IOException
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
    private const val CONTROL_PORT = 8889
    private const val SAMPLE_RATE_ACK_TIMEOUT_MS = 1200

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
        val sampleRateHz: Int? = null,
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
        private set
    private val modeSwitchGeneration = java.util.concurrent.atomic.AtomicLong()

    /**
     * Sends a hardware mode switch command to ESP32 with strict two-way ACK verification.
     */
    fun switchDacMode(
        newMode: DacMode,
        timeoutMs: Int = 1800,
        onResult: ((Boolean, String) -> Unit)? = null,
    ) {
        dacMode = newMode
        val requestGeneration = modeSwitchGeneration.incrementAndGet()
        scope.launch {
            var confirmed = false
            val previousMode = _status.value.activeDacMode
            var responseMsg = "No acknowledgement received from ESP32."
            try {
                val reply = requestUdpCommand(
                    espHost,
                    Esp32WireProtocol.modeCommand(newMode.code),
                    timeoutMs,
                )
                Log.i(TAG, "ESP32 Mode Switch ACK: $reply")

                if (Esp32WireProtocol.isModeAcknowledgement(reply, newMode.code)) {
                    confirmed = true
                    responseMsg = "Hardware confirmed: Switched to ${newMode.label}"
                } else {
                    responseMsg = "ESP32 rejected DAC mode ${newMode.code}: $reply"
                }
            } catch (e: Exception) {
                Log.w(TAG, "Mode switch UDP ACK timeout: ${e.message}")
                responseMsg = "No ACK received from ESP32 ($espHost:8889). Check Wi-Fi connection."
            }

            if (modeSwitchGeneration.get() == requestGeneration) {
                if (!confirmed) dacMode = previousMode
                _status.value = _status.value.copy(
                    activeDacMode = if (confirmed) newMode else previousMode,
                    isHardwareVerified = confirmed,
                    statusMessage = responseMsg,
                    lastVerifiedAt = System.currentTimeMillis()
                )
            }

            withContext(Dispatchers.Main) {
                if (modeSwitchGeneration.get() == requestGeneration) {
                    onResult?.invoke(confirmed, responseMsg)
                }
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
            try {
                val startTime = System.currentTimeMillis()
                val response = requestUdpCommand(host, "GET_STATUS", timeoutMs)
                val latency = System.currentTimeMillis() - startTime
                Log.i(TAG, "ESP32 UDP Status response: $response (latency=${latency}ms)")
                val remoteStatus = Esp32WireProtocol.parseStatus(response)
                    ?: throw IOException("Invalid ESP32 status response")

                val newStatus = _status.value.copy(
                    host = host,
                    activeDacMode = remoteStatus.mode,
                    sampleRateHz = remoteStatus.sampleRateHz,
                    isHardwareVerified = true,
                    latencyMs = latency,
                    rssiDbm = remoteStatus.rssiDbm,
                    bufferPct = remoteStatus.bufferPct,
                    statusMessage = "Verified via UDP in ${latency}ms (Signal: ${remoteStatus.rssiDbm ?: -50} dBm)",
                    lastVerifiedAt = System.currentTimeMillis()
                )
                _status.value = newStatus

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
            }
        }
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var outputStream: OutputStream? = null
    private val isConnecting = AtomicBoolean(false)
    val isConnected = AtomicBoolean(false)
    private val connectionLock = Any()
    private var connectionGeneration = 0L

    // Decoupled asynchronous transmission queue to guarantee zero blocking on ExoPlayer audio thread
    private data class AudioChunk(val bytes: ByteArray, val sampleRateHz: Int)
    private val sendQueue = java.util.concurrent.LinkedBlockingQueue<AudioChunk>(48)
    @Volatile private var senderThread: Thread? = null

    private fun startSenderThread(os: OutputStream, generation: Long) {
        senderThread?.interrupt()
        senderThread = Thread({
            var configuredSampleRate = 0
            var rejectedSampleRate: Int? = null
            try {
                while (isCurrentConnection(generation, os) && !Thread.currentThread().isInterrupted) {
                    val chunk = sendQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    if (!isCurrentConnection(generation, os)) break

                    if (chunk.sampleRateHz != configuredSampleRate) {
                        if (rejectedSampleRate == chunk.sampleRateHz) continue
                        rejectedSampleRate = null
                        val rateReply = requestUdpCommand(
                            espHost,
                            Esp32WireProtocol.sampleRateCommand(chunk.sampleRateHz),
                            SAMPLE_RATE_ACK_TIMEOUT_MS,
                        )
                        if (!Esp32WireProtocol.isSampleRateAcknowledgement(rateReply, chunk.sampleRateHz)) {
                            rejectedSampleRate = chunk.sampleRateHz
                            _status.value = _status.value.copy(
                                statusMessage = "ESP32 rejected ${chunk.sampleRateHz} Hz audio: $rateReply",
                            )
                            continue
                        }
                        configuredSampleRate = chunk.sampleRateHz
                        _status.value = _status.value.copy(sampleRateHz = configuredSampleRate)
                    }

                    os.write(chunk.bytes)
                }
            } catch (e: Exception) {
                if (isCurrentConnection(generation, os)) {
                    Log.w(TAG, "Sender thread error: ${e.message}")
                    closeConnection(generation, os)
                }
            }
        }, "Esp32TcpSender").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun requestUdpCommand(host: String, command: String, timeoutMs: Int): String {
        val address = InetAddress.getByName(host)
        val bytes = command.toByteArray(Charsets.US_ASCII)
        DatagramSocket().use { udp ->
            udp.soTimeout = timeoutMs
            udp.send(DatagramPacket(bytes, bytes.size, address, CONTROL_PORT))

            val responseBytes = ByteArray(128)
            val response = DatagramPacket(responseBytes, responseBytes.size)
            udp.receive(response)
            if (response.address != address || response.port != CONTROL_PORT) {
                throw IOException("Unexpected UDP reply from ${response.address}:${response.port}")
            }
            return String(response.data, 0, response.length, Charsets.US_ASCII).trim()
        }
    }

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
            if (espHost == host && espPort == port) {
                onResult?.invoke(true, "Already connected to $host:$port")
                return
            }
            close()
        }
        if (!isConnecting.compareAndSet(false, true)) return

        val generation = synchronized(connectionLock) { connectionGeneration }
        _status.value = _status.value.copy(isConnecting = true, host = host, port = port, statusMessage = "Connecting to $host:$port...")

        scope.launch {
            var pendingSocket: Socket? = null
            try {
                Log.i(TAG, "Connecting to ESP32 Hi-Fi Audio Server at $host:$port...")
                val sock = Socket().also { pendingSocket = it }
                sock.tcpNoDelay = true
                sock.sendBufferSize = 64 * 1024
                sock.connect(InetSocketAddress(host, port), 3000)

                val os = sock.getOutputStream()
                val ins = sock.getInputStream()

                // Try to read initial greeting from ESP32
                var verifiedMode = dacMode
                var hardwareVerified = false
                try {
                    sock.soTimeout = 1200
                    val reader = ins.bufferedReader()
                    val line = reader.readLine()
                    Log.i(TAG, "Received greeting from ESP32: $line")
                    val greetingMode = line?.let(Esp32WireProtocol::parseGreeting)
                    if (greetingMode != null) {
                        hardwareVerified = true
                        verifiedMode = greetingMode
                    }
                    sock.soTimeout = 0
                } catch (e: Exception) {
                    sock.soTimeout = 0
                }

                val installed = synchronized(connectionLock) {
                    if (connectionGeneration != generation || !isConnecting.get()) {
                        false
                    } else {
                        dacMode = verifiedMode
                        socket = sock
                        outputStream = os
                        espHost = host
                        espPort = port
                        isConnected.set(true)
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
                        startSenderThread(os, generation)
                        true
                    }
                }
                if (!installed) {
                    sock.close()
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    onResult?.invoke(true, "Connected to ESP32 at $host:$port")
                }
                Log.i(TAG, "Connected to ESP32! Bit-perfect 32-bit PCM stream active.")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to connect to ESP32 ($host:$port): ${e.message}")
                runCatching { pendingSocket?.close() }
                closeConnection(generation, null, "Connection failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    onResult?.invoke(false, "Connection failed: ${e.message}")
                }
            } finally {
                synchronized(connectionLock) {
                    if (connectionGeneration == generation) isConnecting.set(false)
                }
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
                val deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(2500)
                while (foundIp == null) {
                    val remainingMs = java.util.concurrent.TimeUnit.NANOSECONDS
                        .toMillis(deadlineNanos - System.nanoTime())
                    if (remainingMs <= 0) break
                    datagramSocket.soTimeout = remainingMs.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

                    val responsePacket = DatagramPacket(buffer, buffer.size)
                    datagramSocket.receive(responsePacket)
                    val response = String(responsePacket.data, 0, responsePacket.length, Charsets.US_ASCII).trim()
                    val discoveredMode = Esp32WireProtocol.parseDiscovery(response) ?: continue
                    val ip = responsePacket.address?.hostAddress ?: continue

                    Log.i(TAG, "Auto-discovered ESP32 Hi-Fi Receiver at IP: $ip")
                    foundIp = ip
                    espHost = ip
                    _status.value = _status.value.copy(
                        host = ip,
                        activeDacMode = discoveredMode,
                        isHardwareVerified = true,
                        statusMessage = "Discovered at $ip (${discoveredMode.label})",
                    )
                    withContext(Dispatchers.Main) {
                        onDiscovered?.invoke(ip)
                    }
                    connect(ip, onResult = { connected, _ ->
                        if (!connected) onFailed?.invoke()
                    })
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
                            connect(ip, onResult = { connected, _ ->
                                if (!connected) onFailed?.invoke()
                            })
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
    @Synchronized
    fun writePcm(buffer: ByteBuffer, inFormat: SampleFormat, sampleRateHz: Int) {
        if (!isConnected.get()) {
            if (!isConnecting.get()) connect()
            if (!isConnecting.get()) return
        }

        try {
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

            // Non-blocking hand-off to dedicated network thread (Zero ExoPlayer thread stalling)
            val chunk = ByteArray(byteCount)
            System.arraycopy(outBytes, 0, chunk, 0, byteCount)
            val queued = AudioChunk(chunk, sampleRateHz)
            if (!sendQueue.offer(queued)) {
                sendQueue.poll() // drop oldest chunk to stay locked to real-time low latency
                sendQueue.offer(queued)
            }
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

    fun close() = closeConnection()

    private fun isCurrentConnection(generation: Long, os: OutputStream): Boolean =
        synchronized(connectionLock) {
            connectionGeneration == generation && outputStream === os && isConnected.get()
        }

    private fun closeConnection(
        expectedGeneration: Long? = null,
        expectedOutput: OutputStream? = null,
        message: String = "Disconnected",
    ) {
        val oldResources = synchronized(connectionLock) {
            if (expectedGeneration != null && connectionGeneration != expectedGeneration) return
            if (expectedOutput != null && outputStream !== expectedOutput) return

            connectionGeneration++
            isConnected.set(false)
            isConnecting.set(false)
            val oldThread = senderThread
            val oldOutput = outputStream
            val oldSocket = socket
            senderThread = null
            outputStream = null
            socket = null
            sendQueue.clear()
            _status.value = _status.value.copy(
                isConnected = false,
                isConnecting = false,
                isHardwareVerified = false,
                sampleRateHz = null,
                latencyMs = null,
                rssiDbm = null,
                bufferPct = null,
                statusMessage = message,
            )
            Triple(oldThread, oldOutput, oldSocket)
        }

        oldResources.first?.interrupt()
        try { oldResources.second?.close() } catch (_: Exception) {}
        try { oldResources.third?.close() } catch (_: Exception) {}
    }

}

internal object Esp32WireProtocol {
    data class DeviceStatus(
        val mode: Esp32SocketStreamer.DacMode,
        val sampleRateHz: Int?,
        val rssiDbm: Int?,
        val bufferPct: String?,
    )

    private val modePattern = Regex("(?:^|[:,\\s])MODE:([12])(?:[,\\s]|$)")
    private val ratePattern = Regex("(?:^|,)RATE:(\\d+)(?:,|$)")
    private val rssiPattern = Regex("(?:^|,)RSSI:(-?\\d+)(?:,|$)")
    private val bufferPattern = Regex("(?:^|,)BUFFER:(100|[0-9]?[0-9])%(?:,|$)")

    fun modeCommand(modeCode: Byte): String = "MODE:${modeCode.toInt()}"

    fun sampleRateCommand(sampleRateHz: Int): String = "RATE:$sampleRateHz"

    fun isModeAcknowledgement(reply: String, expectedMode: Byte): Boolean =
        reply.trim() == "MODE_ACK:${expectedMode.toInt()}"

    fun isSampleRateAcknowledgement(reply: String, expectedRateHz: Int): Boolean =
        reply.trim() == "RATE_ACK:$expectedRateHz"

    fun parseDiscovery(reply: String): Esp32SocketStreamer.DacMode? {
        if (!reply.startsWith("HIFI_DAC_HERE")) return null
        return modePattern.find(reply)?.groupValues?.getOrNull(1)?.toIntOrNull()?.toMode()
    }

    fun parseGreeting(reply: String): Esp32SocketStreamer.DacMode? {
        if (!reply.startsWith("HIFI_CONNECTED")) return null
        return modePattern.find(reply)?.groupValues?.getOrNull(1)?.toIntOrNull()?.toMode()
    }

    fun parseStatus(reply: String): DeviceStatus? {
        if (!reply.startsWith("HIFI_STATUS:")) return null
        val mode = modePattern.find(reply)?.groupValues?.getOrNull(1)?.toIntOrNull()?.toMode() ?: return null
        val sampleRateHz = ratePattern.find(reply)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (reply.contains("RATE:") && sampleRateHz == null) return null
        val rssiDbm = rssiPattern.find(reply)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (reply.contains("RSSI:") && rssiDbm == null) return null
        val bufferPct = bufferPattern.find(reply)?.groupValues?.getOrNull(1)?.let { "$it%" }
        if (reply.contains("BUFFER:") && bufferPct == null) return null

        return DeviceStatus(mode, sampleRateHz, rssiDbm, bufferPct)
    }

    private fun Int.toMode(): Esp32SocketStreamer.DacMode? = when (this) {
        1 -> Esp32SocketStreamer.DacMode.PCM5102A
        2 -> Esp32SocketStreamer.DacMode.INTERNAL_DAC
        else -> null
    }
}
