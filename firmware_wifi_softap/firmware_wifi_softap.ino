/**
 * ============================================================================
 * ESP32 Audiophile Dual-Mode Wireless Audio Receiver (Dynamic Switcher)
 * ============================================================================
 * 
 * Wire Format: Signed 32-bit little-endian interleaved stereo PCM (S32_LE).
 *              8 bytes per frame [Left: 4 bytes, Right: 4 bytes].
 * 
 * Architecture & Concurrency:
 *   - Lock-free SPSC 64 KB Circular Audio Ring Buffer (Acquire/Release Atomics).
 *   - Starvation-Free Pause/Resume Handshake between loop() [Priority 1] 
 *     and audioWorkerTask [Priority 12, Core 1] via FreeRTOS binary semaphores.
 *   - Frame-Aligned (8-byte) ring buffer reset ensuring zero L/R phase swap.
 *   - Dynamic sample-rate-aware I2S timeouts & adaptive prebuffer watermarks.
 *   - Anti-pop DC silence (0x8000) for Built-in 8-bit DAC with TPDF dither.
 *   - Independent GPIO routing: External I2S on GPIO 27/14/22; DAC on GPIO 25/26.
 * 
 * Supported Modes:
 *   MODE 1: External PCM5102A 32-bit I2S DAC (8–192 kHz) - Studio Master Quality
 *           ESP32 GPIO 27 -> BCK (Bit Clock)
 *           ESP32 GPIO 14 -> LCK / WS (Word Select)
 *           ESP32 GPIO 22 -> DIN (Serial Data)
 *           GND           -> SCK (PCM5102A Internal PLL Clock Generation)
 *           (Module Solder Jumpers: FLT->GND, DEMP->GND, XSMT->3.3V, FMT->GND)
 * 
 *   MODE 2: ESP32 Built-in DAC (8–192 kHz) - 8-bit Resolution (~50 dB SNR)
 *           with 1 LSB TPDF Dither (+128 offset) & Mid-scale DC bias (1.65V).
 *           ESP32 GPIO 25 -> Amp Left Channel (DAC1)
 *           ESP32 GPIO 26 -> Amp Right Channel (DAC2)
 *           GND           -> Amp Audio Ground
 * ============================================================================
 */

#include <WiFi.h>
#include <WiFiUdp.h>
#include <ESPmDNS.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "driver/i2s.h"

// Wi-Fi Hotspot Credentials (default: matching Music App)
const char* HOTSPOT_SSID = "FBI-VAN";
const char* HOTSPOT_PASS = "87654321";

const uint16_t TCP_PORT = 8888;
const uint16_t CONTROL_UDP_PORT = 8889;
WiFiServer server(TCP_PORT);
WiFiUDP controlUdp;

// ============================================================================
// HARDWARE DAC DEFINITIONS & DEDICATED PINOUT
// ============================================================================
#define MODE_EXTERNAL_PCM5102A   1
#define MODE_INTERNAL_ESP32_DAC  2

#define I2S_PORT                 I2S_NUM_0

// Dedicated External I2S Pins (Kept completely separate from DAC GPIO 25/26
// to prevent driving MHz digital square waves into analog amplifier inputs)
#define I2S_BCK_PIN              27
#define I2S_WS_PIN               14
#define I2S_DATA_PIN             22

// ============================================================================
// MANUAL MEMORY MANAGEMENT (ZERO DYNAMIC HEAP ALLOCATIONS)
// 64 KB Lockless SPSC Circular Ring Buffer in Internal 32-bit SRAM
// ============================================================================
#define RING_BUFFER_SIZE         65536  // 64 KB (Power-of-2 for O(1) bitmask modulo)
#define RING_BUFFER_MASK         (RING_BUFFER_SIZE - 1)

static uint8_t s_ringbuffer[RING_BUFFER_SIZE] __attribute__((aligned(4)));
static uint32_t s_rb_head = 0; // Written only by the network producer.
static uint32_t s_rb_tail = 0; // Written only by audio consumer (except rb_reset during handshake).

// Statically allocated DMA scratch buffers (Zero heap fragmentation)
#define DMA_CHUNK_SIZE           2048
static uint8_t s_dma_chunk[DMA_CHUNK_SIZE] __attribute__((aligned(4)));
static uint16_t s_dac_chunk[DMA_CHUNK_SIZE / 2] __attribute__((aligned(4)));
static uint8_t s_rx_buf[2048] __attribute__((aligned(4)));

// Active Hardware State
static uint8_t s_active_mode = MODE_INTERNAL_ESP32_DAC;
static uint32_t s_active_sample_rate = 48000;
static bool s_i2s_initialized = false;

// ============================================================================
// STARVATION-FREE HANDSHAKE (Worker Pause / Resume)
// Replaces s_i2s_mutex in audioWorkerTask hot loop to prevent Priority 12 starvation of Priority 1 loop()
// ============================================================================
static volatile bool s_worker_pause_requested = false;
static SemaphoreHandle_t s_worker_paused = nullptr;
static SemaphoreHandle_t s_worker_resume = nullptr;

static void pauseAudioWorker() {
    if (s_worker_paused == nullptr || s_worker_resume == nullptr) return;
    __atomic_store_n(&s_worker_pause_requested, true, __ATOMIC_RELEASE);
    xSemaphoreTake(s_worker_paused, portMAX_DELAY); // Wait for worker to safely park
}

static void resumeAudioWorker() {
    if (s_worker_paused == nullptr || s_worker_resume == nullptr) return;
    __atomic_store_n(&s_worker_pause_requested, false, __ATOMIC_RELEASE);
    xSemaphoreGive(s_worker_resume); // Let worker resume streaming
}

// Anti-Jitter Pre-buffering Hysteresis
static volatile bool s_prebuffering = true;

// Adaptive Prebuffer Watermark: targets ~40 ms of 32-bit stereo audio, clamped to [4096, 32768]
static inline size_t getPrebufferWatermark(uint32_t sample_rate) {
    if (sample_rate == 0) sample_rate = 48000;
    uint32_t wm = (sample_rate * 8 * 40) / 1000; // rate * 8 bytes/frame * 40ms / 1000ms
    if (wm < 4096) return 4096;
    if (wm > 32768) return 32768;
    return static_cast<size_t>(wm);
}

// Fast PRNG (XorShift32) for TPDF dither
static inline uint32_t fast_rand32() {
    static uint32_t s_rng = 0x87654321;
    s_rng ^= s_rng << 13;
    s_rng ^= s_rng >> 17;
    s_rng ^= s_rng << 5;
    return s_rng;
}

// ============================================================================
// SPSC LOCKLESS RING BUFFER OPERATIONS
// ============================================================================
static inline size_t rb_available_write() {
    uint32_t head = __atomic_load_n(&s_rb_head, __ATOMIC_RELAXED);
    uint32_t tail = __atomic_load_n(&s_rb_tail, __ATOMIC_ACQUIRE);
    return RING_BUFFER_SIZE - 1 - ((head - tail) & RING_BUFFER_MASK);
}

static inline size_t rb_available_read() {
    uint32_t head = __atomic_load_n(&s_rb_head, __ATOMIC_ACQUIRE);
    uint32_t tail = __atomic_load_n(&s_rb_tail, __ATOMIC_RELAXED);
    return (head - tail) & RING_BUFFER_MASK;
}

// Optimized two-segment memcpy write
static inline bool rb_write(const uint8_t *data, size_t length) {
    if (length > rb_available_write()) return false;

    uint32_t head = __atomic_load_n(&s_rb_head, __ATOMIC_RELAXED);
    size_t first = min(length, (size_t)(RING_BUFFER_SIZE - head));
    memcpy(s_ringbuffer + head, data, first);
    if (length > first) {
        memcpy(s_ringbuffer, data + first, length - first);
    }
    head = (head + length) & RING_BUFFER_MASK;
    __atomic_store_n(&s_rb_head, head, __ATOMIC_RELEASE);
    return true;
}

// Optimized two-segment memcpy read
static inline void rb_read(uint8_t *dest, size_t length) {
    uint32_t tail = __atomic_load_n(&s_rb_tail, __ATOMIC_RELAXED);
    size_t first = min(length, (size_t)(RING_BUFFER_SIZE - tail));
    memcpy(dest, s_ringbuffer + tail, first);
    if (length > first) {
        memcpy(dest + first, s_ringbuffer, length - first);
    }
    tail = (tail + length) & RING_BUFFER_MASK;
    __atomic_store_n(&s_rb_tail, tail, __ATOMIC_RELEASE);
}

/**
 * Caller MUST pause audioWorkerTask first via pauseAudioWorker().
 * 32-bit stereo frame = 8 bytes.
 * For a brand new stream: both head & tail reset to 0.
 * For an in-stream config change: head is rounded down to an 8-byte frame boundary (~7u)
 * and tail aligned to it, preventing L/R phase swap and bit-shift static.
 */
static inline void rb_reset(bool new_stream) {
    uint32_t head = new_stream ? 0u : (__atomic_load_n(&s_rb_head, __ATOMIC_RELAXED) & ~7u);
    if (new_stream) {
        __atomic_store_n(&s_rb_head, 0u, __ATOMIC_RELEASE);
    }
    __atomic_store_n(&s_rb_tail, head, __ATOMIC_RELEASE);
    s_prebuffering = true;
}

// ============================================================================
// DYNAMIC I2S HARDWARE CONTROLLER & ANTI-POP SILENCE
// ============================================================================
static bool isSupportedSampleRate(uint32_t sample_rate) {
    switch (sample_rate) {
        case 8000: case 11025: case 12000: case 16000: case 22050: case 24000:
        case 32000: case 44100: case 48000: case 64000: case 88200: case 96000:
        case 176400: case 192000:
            return true;
        default:
            return false;
    }
}

/**
 * Feeds DC-safe silence to the active output without causing speaker pops:
 *   - For External PCM5102A: writes zeroed DMA (0 V true digital ground).
 *   - For Internal DAC: writes mid-scale 0x8000 (1.65 V DC mid-rail) so the DAC
 *     does not violently step between 1.65 V and 0 V.
 */
static void outputDmaSilence(uint8_t mode) {
    if (mode == MODE_INTERNAL_ESP32_DAC) {
        // Output mid-scale silence on stack (zero BSS DRAM footprint)
        uint16_t silence[64];
        for (size_t i = 0; i < 64; i++) {
            silence[i] = 0x8000;
        }
        size_t written = 0;
        for (int b = 0; b < 16; b++) {
            i2s_write(I2S_PORT, silence, sizeof(silence), &written, pdMS_TO_TICKS(10));
        }
    } else {
        i2s_zero_dma_buffer(I2S_PORT);
    }
}

static esp_err_t installI2SHardware(uint8_t mode, uint32_t sample_rate) {
    i2s_config_t i2s_config = {};
    i2s_config.mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX);
    i2s_config.sample_rate = sample_rate;
    i2s_config.channel_format = I2S_CHANNEL_FMT_RIGHT_LEFT;
    i2s_config.intr_alloc_flags = ESP_INTR_FLAG_LEVEL1;
    i2s_config.dma_buf_count = 8;
    i2s_config.dma_buf_len = 480; // 480 frames * 8 B = 3840 B (fits within 4092-byte hardware descriptor limit)

    if (mode == MODE_EXTERNAL_PCM5102A) {
        i2s_config.tx_desc_auto_clear = true;
        i2s_config.bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT;
        i2s_config.communication_format = I2S_COMM_FORMAT_STAND_I2S;
        i2s_config.use_apll = true;
    } else {
        // In internal DAC mode, tx_desc_auto_clear must be false to prevent DMA from auto-clearing to 0x0000 (0V thump)
        i2s_config.tx_desc_auto_clear = false;
        i2s_config.mode = (i2s_mode_t)(i2s_config.mode | I2S_MODE_DAC_BUILT_IN);
        i2s_config.bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT;
        i2s_config.communication_format = I2S_COMM_FORMAT_STAND_MSB;
        i2s_config.use_apll = false;
    }

    esp_err_t error = i2s_driver_install(I2S_PORT, &i2s_config, 0, NULL);
    if (error != ESP_OK) return error;

    if (mode == MODE_EXTERNAL_PCM5102A) {
        // Release GPIO 25/26 from DAC analog matrix
        i2s_set_dac_mode(I2S_DAC_CHANNEL_DISABLE);
        i2s_pin_config_t pin_config = {};
        pin_config.bck_io_num = I2S_BCK_PIN;
        pin_config.ws_io_num = I2S_WS_PIN;
        pin_config.data_out_num = I2S_DATA_PIN;
        pin_config.data_in_num = I2S_PIN_NO_CHANGE;
        error = i2s_set_pin(I2S_PORT, &pin_config);
    } else {
        error = i2s_set_dac_mode(I2S_DAC_CHANNEL_BOTH_EN);
    }

    if (error == ESP_OK) {
        outputDmaSilence(mode);
    } else {
        i2s_set_dac_mode(I2S_DAC_CHANNEL_DISABLE);
        i2s_driver_uninstall(I2S_PORT);
    }
    return error;
}

static inline uint8_t getActiveMode() {
    return __atomic_load_n(&s_active_mode, __ATOMIC_ACQUIRE);
}

static inline uint32_t getActiveSampleRate() {
    return __atomic_load_n(&s_active_sample_rate, __ATOMIC_ACQUIRE);
}

static bool configureI2SHardware(uint8_t new_mode, uint32_t new_sample_rate) {
    if (new_mode != MODE_EXTERNAL_PCM5102A && new_mode != MODE_INTERNAL_ESP32_DAC) return false;
    if (!isSupportedSampleRate(new_sample_rate)) return false;

    // Pause audioWorkerTask to acquire exclusive hardware & ring buffer control
    pauseAudioWorker();

    // Fast Pop-Free Rate Switching (no driver reinstall if mode is unchanged)
    if (s_i2s_initialized && new_mode == s_active_mode) {
        outputDmaSilence(new_mode);
        i2s_bits_per_sample_t bits = (new_mode == MODE_EXTERNAL_PCM5102A) 
            ? I2S_BITS_PER_SAMPLE_32BIT 
            : I2S_BITS_PER_SAMPLE_16BIT;
        esp_err_t clk_error = i2s_set_clk(I2S_PORT, new_sample_rate, bits, I2S_CHANNEL_STEREO);
        if (clk_error == ESP_OK) {
            __atomic_store_n(&s_active_sample_rate, new_sample_rate, __ATOMIC_RELEASE);
            rb_reset(false);
            resumeAudioWorker();
            Serial.printf("\n[Audio Engine] >>> Sample rate updated to %u Hz (instant clock switch)\n",
                static_cast<unsigned int>(new_sample_rate));
            return true;
        }
    }

    uint8_t previous_mode = s_active_mode;
    uint32_t previous_sample_rate = s_active_sample_rate;
    if (s_i2s_initialized) {
        outputDmaSilence(previous_mode);
        i2s_set_dac_mode(I2S_DAC_CHANNEL_DISABLE);
        esp_err_t uninstall_error = i2s_driver_uninstall(I2S_PORT);
        if (uninstall_error != ESP_OK) {
            resumeAudioWorker();
            Serial.printf("[Audio Engine] I2S uninstall failed (%d); keeping current mode.\n", uninstall_error);
            return false;
        }
        s_i2s_initialized = false;
    }

    esp_err_t error = installI2SHardware(new_mode, new_sample_rate);
    if (error == ESP_OK) {
        __atomic_store_n(&s_active_mode, new_mode, __ATOMIC_RELEASE);
        __atomic_store_n(&s_active_sample_rate, new_sample_rate, __ATOMIC_RELEASE);
        s_i2s_initialized = true;
        rb_reset(false);
        resumeAudioWorker();

        if (new_mode == MODE_EXTERNAL_PCM5102A) {
            Serial.printf("\n[Audio Engine] >>> External PCM5102A active at %u Hz (BCK:%d, WS:%d, DIN:%d, APLL)\n",
                static_cast<unsigned int>(new_sample_rate), I2S_BCK_PIN, I2S_WS_PIN, I2S_DATA_PIN);
        } else {
            Serial.printf("\n[Audio Engine] >>> Built-in ESP32 DAC active at %u Hz (DAC1:25, DAC2:26, 8-bit TPDF dither)\n",
                static_cast<unsigned int>(new_sample_rate));
        }
        return true;
    }

    // Keep the last working output available if the requested configuration fails
    esp_err_t restore_error = installI2SHardware(previous_mode, previous_sample_rate);
    s_i2s_initialized = (restore_error == ESP_OK);
    resumeAudioWorker();
    Serial.printf("[Audio Engine] I2S configuration failed (%d); restore %s (%d).\n",
        error, s_i2s_initialized ? "succeeded" : "failed", restore_error);
    return false;
}

// ============================================================================
// REAL-TIME AUDIO DMA WORKER (Core 1, Priority 12)
// Hot loop does not take any mutex; strictly yields on s_worker_pause_requested
// ============================================================================
void audioWorkerTask(void *param) {
    while (true) {
        // Check for pause handshake from loop()
        if (__atomic_load_n(&s_worker_pause_requested, __ATOMIC_ACQUIRE)) {
            xSemaphoreGive(s_worker_paused);
            xSemaphoreTake(s_worker_resume, portMAX_DELAY);
        }

        if (!s_i2s_initialized) {
            vTaskDelay(pdMS_TO_TICKS(5));
            continue;
        }

        uint32_t active_rate = getActiveSampleRate();
        size_t watermark = getPrebufferWatermark(active_rate);
        size_t available = rb_available_read();

        // Anti-crackle pre-buffering: ensure adaptive cushion before draining ring buffer
        if (s_prebuffering) {
            if (available < watermark) {
                vTaskDelay(pdMS_TO_TICKS(2));
                continue;
            }
            s_prebuffering = false;
        }

        uint8_t mode = s_active_mode;

        // Buffer starvation protection: if drained below chunk size, output silence and pre-buffer again
        if (available < DMA_CHUNK_SIZE) {
            s_prebuffering = true;
            outputDmaSilence(mode);
            vTaskDelay(pdMS_TO_TICKS(2));
            continue;
        }

        rb_read(s_dma_chunk, DMA_CHUNK_SIZE);
        const uint8_t *output = s_dma_chunk;
        size_t output_length = DMA_CHUNK_SIZE;

        if (mode == MODE_INTERNAL_ESP32_DAC) {
            // Built-in DAC consumes the high 8 bits of each 16-bit slot.
            // 1. TPDF dither: Extracts two uniform 8-bit random variables from upper bits of a single fast_rand32().
            // 2. DC-bias correction: +128 offset ensures symmetric rounding around 0x8000 midscale silence.
            // 3. Channel order: i ^ 1 handles ESP32 legacy I2S FIFO 32-bit halfword swap for proper L/R channel mapping.
            const int32_t *input = reinterpret_cast<const int32_t *>(s_dma_chunk);
            const size_t sample_count = DMA_CHUNK_SIZE / sizeof(int32_t);
            for (size_t i = 0; i < sample_count; i++) {
                int32_t s16 = input[i] >> 16; // 16-bit signed [-32768, 32767]
                uint32_t r = fast_rand32();
                int32_t dither = static_cast<int32_t>(r >> 24) - static_cast<int32_t>((r >> 16) & 0xFF);
                int32_t val = s16 + dither + 32768 + 128; // Unsigned with +128 rounding
                if (val < 0) val = 0;
                else if (val > 65535) val = 65535;
                s_dac_chunk[i ^ 1] = static_cast<uint16_t>(val & 0xFF00);
            }
            output = reinterpret_cast<const uint8_t *>(s_dac_chunk);
            output_length = sample_count * sizeof(uint16_t);
        }

        // Dynamic I2S write timeout: at 8 kHz, 1 DMA descriptor (480 frames) takes 60ms to drain.
        // Scale timeout dynamically so low sample rates never timeout prematurely.
        uint32_t calc_ms = (active_rate > 0) ? ((480UL * 2000UL) / active_rate) : 100UL;
        uint32_t timeout_ms = (calc_ms > 100UL) ? calc_ms : 100UL;
        TickType_t write_timeout = pdMS_TO_TICKS(timeout_ms);

        size_t offset = 0;
        while (offset < output_length) {
            size_t written = 0;
            esp_err_t write_error = i2s_write(I2S_PORT, output + offset, output_length - offset, &written, write_timeout);
            if (write_error != ESP_OK || written == 0) break;
            offset += written;
        }

        if (offset != output_length) {
            // Note: avoid Serial.printf in real-time audio thread to prevent UART stalls
            vTaskDelay(pdMS_TO_TICKS(1));
        }
    }
}

// ============================================================================
// OUT-OF-BAND UDP CONTROL (PORT 8889)
// ============================================================================
static void sendUdpReply(const IPAddress &address, uint16_t port, const char *message) {
    if (!controlUdp.beginPacket(address, port)) return;
    controlUdp.write(reinterpret_cast<const uint8_t *>(message), strlen(message));
    controlUdp.endPacket();
}

static void handleControlUdp() {
    int packet_size = controlUdp.parsePacket();
    if (packet_size <= 0) return;

    char command[96];
    size_t bytes_to_read = min(packet_size, static_cast<int>(sizeof(command) - 1));
    int bytes_read = controlUdp.read(reinterpret_cast<uint8_t *>(command), bytes_to_read);
    if (bytes_read <= 0) return;
    command[bytes_read] = '\0';
    // Discard any datagram tail larger than our command buffer
    while (controlUdp.available()) controlUdp.read();

    // Trim trailing whitespace and carriage returns/newlines
    char *p = command + strlen(command) - 1;
    while (p >= command && (*p == ' ' || *p == '\r' || *p == '\n')) {
        *p = '\0';
        p--;
    }

    IPAddress remote_address = controlUdp.remoteIP();
    uint16_t remote_port = controlUdp.remotePort();
    char reply[128];

    if (strcmp(command, "HIFI_DISCOVER") == 0) {
        snprintf(reply, sizeof(reply), "HIFI_DAC_HERE MODE:%u",
            static_cast<unsigned int>(getActiveMode()));
        sendUdpReply(remote_address, remote_port, reply);
        return;
    }

    if (strcmp(command, "GET_STATUS") == 0) {
        int rssi = (WiFi.getMode() == WIFI_STA && WiFi.status() == WL_CONNECTED) ? WiFi.RSSI() : 0;
        unsigned int fill_pct = (rb_available_read() * 100U) / (RING_BUFFER_SIZE - 1);
        if (fill_pct > 100) fill_pct = 100;
        snprintf(reply, sizeof(reply), "HIFI_STATUS:MODE:%u,RATE:%u,RSSI:%d,BUFFER:%u%%",
            static_cast<unsigned int>(getActiveMode()),
            static_cast<unsigned int>(getActiveSampleRate()), rssi, fill_pct);
        sendUdpReply(remote_address, remote_port, reply);
        return;
    }

    if (strncmp(command, "MODE:", 5) == 0) {
        char *end = nullptr;
        long requested_mode = strtol(command + 5, &end, 10);
        while (end != nullptr && (*end == ' ' || *end == '\r' || *end == '\n')) end++;

        bool valid_mode = end != command + 5 && end != nullptr && *end == '\0' &&
            (requested_mode == MODE_EXTERNAL_PCM5102A || requested_mode == MODE_INTERNAL_ESP32_DAC);
        bool applied = false;
        if (valid_mode) {
            uint8_t mode = static_cast<uint8_t>(requested_mode);
            // Verify s_i2s_initialized is true before short-circuiting
            applied = (s_i2s_initialized && mode == getActiveMode()) ||
                configureI2SHardware(mode, getActiveSampleRate());
        }

        snprintf(reply, sizeof(reply), "%s:%ld", applied ? "MODE_ACK" : "MODE_NACK", requested_mode);
        sendUdpReply(remote_address, remote_port, reply);
        return;
    }

    if (strncmp(command, "RATE:", 5) == 0) {
        char *end = nullptr;
        long requested_rate = strtol(command + 5, &end, 10);
        while (end != nullptr && (*end == ' ' || *end == '\r' || *end == '\n')) end++;

        bool valid_rate = end != nullptr && end != command + 5 && *end == '\0' &&
            requested_rate > 0 && isSupportedSampleRate(static_cast<uint32_t>(requested_rate));
        bool applied = false;
        if (valid_rate) {
            uint32_t sample_rate = static_cast<uint32_t>(requested_rate);
            // Verify s_i2s_initialized is true before short-circuiting
            applied = (s_i2s_initialized && sample_rate == getActiveSampleRate()) ||
                configureI2SHardware(getActiveMode(), sample_rate);
        }

        snprintf(reply, sizeof(reply), "%s:%ld", applied ? "RATE_ACK" : "RATE_NACK", requested_rate);
        sendUdpReply(remote_address, remote_port, reply);
        return;
    }

    sendUdpReply(remote_address, remote_port, "UNKNOWN_COMMAND");
}

// ============================================================================
// SYSTEM SETUP
// ============================================================================
void setup() {
    Serial.begin(115200);
    delay(500);

    Serial.println("\n=======================================================");
    Serial.println("  ESP32 Audiophile Dual-Mode Wireless Audio Receiver   ");
    Serial.println("=======================================================");

    s_worker_paused = xSemaphoreCreateBinary();
    s_worker_resume = xSemaphoreCreateBinary();

    if (s_worker_paused == nullptr || s_worker_resume == nullptr || !configureI2SHardware(MODE_INTERNAL_ESP32_DAC, 48000)) {
        Serial.println("[Audio Engine] Fatal: could not initialize the I2S output. Restarting...");
        delay(3000);
        ESP.restart();
    }

    // Worker pinned to Core 1 at priority 12 (safely below Wi-Fi/lwIP system tasks at 18-20)
    // 6144-byte stack to safely accommodate formatted I/O and arithmetic
    BaseType_t task_created = xTaskCreatePinnedToCore(
        audioWorkerTask,
        "AudioDMAWorker",
        6144,
        NULL,
        12,
        NULL,
        1
    );
    if (task_created != pdPASS) {
        Serial.println("[Audio Engine] Fatal: could not start the I2S worker task. Restarting...");
        delay(3000);
        ESP.restart();
    }

    // Wi-Fi Connection Event Handler (ensures modem sleep stays disabled across reconnects)
    WiFi.onEvent([](WiFiEvent_t event, WiFiEventInfo_t info) {
        if (event == ARDUINO_EVENT_WIFI_STA_CONNECTED) {
            WiFi.setSleep(false);
        }
    });

    // Connect to Phone Hotspot
    Serial.print("[Wi-Fi] Connecting to Hotspot: ");
    Serial.println(HOTSPOT_SSID);

    WiFi.mode(WIFI_STA);
    WiFi.begin(HOTSPOT_SSID, HOTSPOT_PASS);
    WiFi.setSleep(false); // Disable modem sleep to prevent DTIM wake latency & jitter

    unsigned long startAttempt = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - startAttempt < 10000) {
        delay(300);
        Serial.print(".");
    }

    if (WiFi.status() == WL_CONNECTED) {
        WiFi.setSleep(false);
        Serial.println("\n[Wi-Fi] Connected to Phone Hotspot successfully!");
        Serial.print("[Wi-Fi] ESP32 IP Address: ");
        Serial.println(WiFi.localIP());
    } else {
        // Fallback: Standalone AP if phone hotspot is not found
        Serial.println("\n[Wi-Fi] Hotspot not found. Starting Standalone AP mode...");
        WiFi.mode(WIFI_AP);
        if (!WiFi.softAP("ESP32-HiFi-Hotspot", "12345678")) {
            Serial.println("[Wi-Fi] Fatal: failed to start the standalone AP. Restarting...");
            delay(3000);
            ESP.restart();
        }
        WiFi.setSleep(false);
        Serial.print("[Wi-Fi] AP Started. ESP32 IP: ");
        Serial.println(WiFi.softAPIP());
    }

    if (controlUdp.begin(CONTROL_UDP_PORT)) {
        Serial.printf("[Control] UDP discovery and control listening on port %u.\n",
            static_cast<unsigned int>(CONTROL_UDP_PORT));
    } else {
        Serial.printf("[Control] Failed to start UDP listener on port %u.\n",
            static_cast<unsigned int>(CONTROL_UDP_PORT));
    }

    // Start mDNS responder so the app can always resolve music-dac.local
    if (MDNS.begin("music-dac")) {
        MDNS.addService("audio-hifi", "tcp", TCP_PORT);
        Serial.println("[mDNS] Responder active at music-dac.local");
    }

    server.begin();
    server.setNoDelay(true);
    Serial.printf("[Server] TCP Audio Server listening on port %d...\n", TCP_PORT);
    Serial.println("[Ready] In the Music app: Select 'ESP32 Hi-Fi Direct' and pick your DAC mode!");
}

// ============================================================================
// NETWORK RECEIVER LOOP (Core 1, Priority 1)
// ============================================================================
void loop() {
    handleControlUdp();

    // Check for Wi-Fi reconnection if STA disconnected
    if (WiFi.getMode() == WIFI_STA && WiFi.status() != WL_CONNECTED) {
        static unsigned long s_last_reconnect_attempt = 0;
        if (millis() - s_last_reconnect_attempt > 10000) {
            s_last_reconnect_attempt = millis();
            WiFi.reconnect();
        }
    }

    WiFiClient client = server.available();
    if (client) {
        Serial.println("\n[Stream] >>> Phone connected! Streaming active.");
        client.setNoDelay(true);
        client.print("HIFI_CONNECTED MODE:");
        client.println(getActiveMode());

        // Guarantee fresh frame alignment on new connection
        pauseAudioWorker();
        rb_reset(true);
        resumeAudioWorker();

        uint64_t totalBytes = 0; // 64-bit to prevent overflow on long sessions
        unsigned long lastStat = millis();
        unsigned long lastRxTime = millis();
        unsigned long lastUdpPoll = millis();

        while (client.connected()) {
            // Check for new client connection to preempt and recover from abrupt reconnects
            if (server.hasClient()) {
                Serial.println("[Stream] New incoming client detected; preempting existing stream.");
                break;
            }

            // Poll UDP control periodically (~every 10ms) without choking throughput
            if (millis() - lastUdpPoll >= 10) {
                handleControlUdp();
                lastUdpPoll = millis();
            }

            int available = client.available();
            if (available > 0) {
                int toRead = min(available, (int)sizeof(s_rx_buf));
                size_t freeSpace = rb_available_write();
                toRead = min(toRead, (int)freeSpace);

                if (toRead > 0) {
                    int bytesRead = client.read(s_rx_buf, toRead);
                    if (bytesRead > 0) {
                        if (rb_write(s_rx_buf, bytesRead)) {
                            totalBytes += bytesRead;
                            lastRxTime = millis();
                        }
                    }
                } else {
                    // Buffer is full (normal TCP backpressure); yield briefly to allow audioWorkerTask to drain
                    vTaskDelay(pdMS_TO_TICKS(1));
                    lastRxTime = millis(); // Don't count normal TCP backpressure as inactivity
                }
            } else {
                vTaskDelay(pdMS_TO_TICKS(1));
                // Inactivity timeout: 30 seconds of absolute silence/idle before dropping dead half-open sockets
                if (millis() - lastRxTime > 30000) {
                    Serial.println("[Stream] TCP client inactive for 30s, dropping dead connection.");
                    break;
                }
            }

            if (millis() - lastStat > 3000) {
                if (totalBytes > 0) {
                    Serial.printf("[Stream] Active | Mode: %s | Total: %llu KB | Buffer fill: %u%%\n",
                        getActiveMode() == MODE_EXTERNAL_PCM5102A ? "PCM5102A (32-bit)" : "Internal DAC",
                        totalBytes / 1024ULL,
                        static_cast<unsigned int>((rb_available_read() * 100) / (RING_BUFFER_SIZE - 1))
                    );
                }
                lastStat = millis();
            }
        }

        // Drain any trailing bytes left in socket before final close
        while (client.available() > 0 && rb_available_write() > 0) {
            int toRead = min(client.available(), (int)sizeof(s_rx_buf));
            toRead = min(toRead, (int)rb_available_write());
            int n = client.read(s_rx_buf, toRead);
            if (n > 0) rb_write(s_rx_buf, n);
        }

        client.stop();

        pauseAudioWorker();
        outputDmaSilence(getActiveMode());
        rb_reset(true);
        resumeAudioWorker();

        Serial.println("[Stream] Phone disconnected.");
    }

    delay(10);
}
