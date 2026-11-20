/**
 * ============================================================================
 * ESP32 Audiophile Wireless Audio Receiver (Dual-Mode Dynamic Switcher)
 * ============================================================================
 * 
 * Manual Memory Management: Zero runtime malloc/free in audio loop.
 * SPSC 64 KB Circular Audio Ring Buffer.
 * Phone App Dynamic Switching: Switch between PCM5102A & Built-in DAC on-the-fly!
 * 
 * Supported Modes:
 *   MODE 1: External PCM5102A 32-bit I2S DAC (8–192 kHz)
 *           ESP32 GPIO 26 -> BCK, GPIO 25 -> LCK, GPIO 22 -> DIN, GND -> SCK
 *   MODE 2: ESP32 Built-in DAC (8–192 kHz)
 *           ESP32 GPIO 25 -> Amp Left (L+), GPIO 26 -> Amp Right (R+), GND -> Ground
 * ============================================================================
 */

#include <WiFi.h>
#include <WiFiUdp.h>
#include <ESPmDNS.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "driver/i2s.h"

// Wi-Fi Hotspot Credentials (matching Music App default)
const char* HOTSPOT_SSID = "FBI-VAN";
const char* HOTSPOT_PASS = "87654321";

const uint16_t TCP_PORT = 8888;
const uint16_t CONTROL_UDP_PORT = 8889;
WiFiServer server(TCP_PORT);
WiFiUDP controlUdp;

// ============================================================================
// HARDWARE DAC DEFINITIONS
// ============================================================================
#define MODE_EXTERNAL_PCM5102A   1
#define MODE_INTERNAL_ESP32_DAC  2

#define I2S_PORT                 I2S_NUM_0
#define I2S_BCK_PIN              26
#define I2S_WS_PIN               25
#define I2S_DATA_PIN             22

// ============================================================================
// MANUAL MEMORY MANAGEMENT (ZERO DYNAMIC HEAP ALLOCATIONS)
// 64 KB Lockless SPSC Circular Ring Buffer in Internal 32-bit SRAM
// ============================================================================
#define RING_BUFFER_SIZE         65536  // 64 KB (Power-of-2 for O(1) bitmask modulo)
#define RING_BUFFER_MASK         (RING_BUFFER_SIZE - 1)

static uint8_t s_ringbuffer[RING_BUFFER_SIZE] __attribute__((aligned(4)));
static uint32_t s_rb_head = 0; // Written only by the network producer.
static uint32_t s_rb_tail = 0; // Written only by the audio consumer.

// Statically allocated DMA scratch buffers (Zero heap fragmentation)
#define DMA_CHUNK_SIZE           2048
static uint8_t s_dma_chunk[DMA_CHUNK_SIZE] __attribute__((aligned(4)));
static uint16_t s_dac_chunk[DMA_CHUNK_SIZE / 2] __attribute__((aligned(4)));
static uint8_t s_rx_buf[4096] __attribute__((aligned(4)));

// Active Hardware State (Default: Internal Built-in DAC for testing without external board)
static uint8_t s_active_mode = MODE_INTERNAL_ESP32_DAC;
static uint32_t s_active_sample_rate = 48000;
static bool s_i2s_initialized = false;
static SemaphoreHandle_t s_i2s_mutex = nullptr;

// Anti-Jitter Pre-buffering Hysteresis (prevents crackle / stutter on network hiccups)
#define PREBUFFER_WATERMARK      16384  // 16 KB cushion (~93ms of 44.1k/32-bit audio)
static volatile bool s_prebuffering = true;

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

static inline bool rb_write(const uint8_t *data, size_t length) {
    if (length > rb_available_write()) return false;

    uint32_t head = __atomic_load_n(&s_rb_head, __ATOMIC_RELAXED);
    for (size_t i = 0; i < length; i++) {
        s_ringbuffer[head] = data[i];
        head = (head + 1) & RING_BUFFER_MASK;
    }
    __atomic_store_n(&s_rb_head, head, __ATOMIC_RELEASE);
    return true;
}

static inline void rb_read(uint8_t *dest, size_t length) {
    uint32_t tail = __atomic_load_n(&s_rb_tail, __ATOMIC_RELAXED);
    for (size_t i = 0; i < length; i++) {
        dest[i] = s_ringbuffer[tail];
        tail = (tail + 1) & RING_BUFFER_MASK;
    }
    __atomic_store_n(&s_rb_tail, tail, __ATOMIC_RELEASE);
}

static inline void rb_reset() {
    uint32_t head = __atomic_load_n(&s_rb_head, __ATOMIC_ACQUIRE);
    __atomic_store_n(&s_rb_tail, head, __ATOMIC_RELEASE);
    s_prebuffering = true;
}

// ============================================================================
// DYNAMIC I2S HARDWARE CONTROLLER
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

static esp_err_t installI2SHardware(uint8_t mode, uint32_t sample_rate) {
    i2s_config_t i2s_config = {};
    i2s_config.mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX);
    i2s_config.sample_rate = sample_rate;
    i2s_config.channel_format = I2S_CHANNEL_FMT_RIGHT_LEFT;
    i2s_config.intr_alloc_flags = ESP_INTR_FLAG_LEVEL1;
    i2s_config.dma_buf_count = 8;
    i2s_config.dma_buf_len = 512;
    i2s_config.tx_desc_auto_clear = true;

    if (mode == MODE_EXTERNAL_PCM5102A) {
        i2s_config.bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT;
        i2s_config.communication_format = I2S_COMM_FORMAT_STAND_I2S;
        i2s_config.use_apll = true;
    } else {
        i2s_config.mode = (i2s_mode_t)(i2s_config.mode | I2S_MODE_DAC_BUILT_IN);
        i2s_config.bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT;
        i2s_config.communication_format = I2S_COMM_FORMAT_STAND_MSB;
        i2s_config.use_apll = false;
    }

    esp_err_t error = i2s_driver_install(I2S_PORT, &i2s_config, 0, NULL);
    if (error != ESP_OK) return error;

    if (mode == MODE_EXTERNAL_PCM5102A) {
        // Explicitly release GPIO 25/26 from the internal DAC analog matrix
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

    if (error == ESP_OK) error = i2s_zero_dma_buffer(I2S_PORT);
    if (error != ESP_OK) {
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
    if (s_i2s_mutex == nullptr || xSemaphoreTake(s_i2s_mutex, portMAX_DELAY) != pdTRUE) return false;

    uint8_t previous_mode = s_active_mode;
    uint32_t previous_sample_rate = s_active_sample_rate;
    if (s_i2s_initialized) {
        i2s_zero_dma_buffer(I2S_PORT);
        i2s_set_dac_mode(I2S_DAC_CHANNEL_DISABLE);
        esp_err_t uninstall_error = i2s_driver_uninstall(I2S_PORT);
        if (uninstall_error != ESP_OK) {
            xSemaphoreGive(s_i2s_mutex);
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
        rb_reset();
        xSemaphoreGive(s_i2s_mutex);

        if (new_mode == MODE_EXTERNAL_PCM5102A) {
            Serial.printf("\n[Audio Engine] >>> External PCM5102A active at %u Hz (32-bit slots, APLL)\n",
                static_cast<unsigned int>(new_sample_rate));
        } else {
            Serial.printf("\n[Audio Engine] >>> Built-in ESP32 DAC active at %u Hz (GPIO 25/26)\n",
                static_cast<unsigned int>(new_sample_rate));
        }
        return true;
    }

    // Keep the last working output available if the requested configuration fails.
    esp_err_t restore_error = installI2SHardware(previous_mode, previous_sample_rate);
    s_i2s_initialized = restore_error == ESP_OK;
    xSemaphoreGive(s_i2s_mutex);
    Serial.printf("[Audio Engine] I2S configuration failed (%d); restore %s (%d).\n",
        error, s_i2s_initialized ? "succeeded" : "failed", restore_error);
    return false;
}

// ============================================================================
// REAL-TIME AUDIO DMA WORKER
// ============================================================================
void audioWorkerTask(void *param) {
    while (true) {
        if (s_i2s_mutex == nullptr || xSemaphoreTake(s_i2s_mutex, portMAX_DELAY) != pdTRUE) {
            vTaskDelay(1);
            continue;
        }

        if (!s_i2s_initialized) {
            xSemaphoreGive(s_i2s_mutex);
            vTaskDelay(1);
            continue;
        }

        size_t available = rb_available_read();

        // Anti-crackle pre-buffering: ensure 16 KB cushion before draining ring buffer
        if (s_prebuffering) {
            if (available < PREBUFFER_WATERMARK) {
                xSemaphoreGive(s_i2s_mutex);
                vTaskDelay(2);
                continue;
            }
            s_prebuffering = false;
        }

        // Buffer starvation protection: if drained below chunk size, zero DMA to prevent pop and pre-buffer again
        if (available < DMA_CHUNK_SIZE) {
            s_prebuffering = true;
            i2s_zero_dma_buffer(I2S_PORT);
            xSemaphoreGive(s_i2s_mutex);
            vTaskDelay(2);
            continue;
        }

        uint8_t mode = s_active_mode;
        rb_read(s_dma_chunk, DMA_CHUNK_SIZE);
        const uint8_t *output = s_dma_chunk;
        size_t output_length = DMA_CHUNK_SIZE;

        if (mode == MODE_INTERNAL_ESP32_DAC) {
            // The built-in DAC consumes the high 8 bits of each 16-bit slot.
            const int32_t *input = reinterpret_cast<const int32_t *>(s_dma_chunk);
            const size_t sample_count = DMA_CHUNK_SIZE / sizeof(int32_t);
            for (size_t i = 0; i < sample_count; i++) {
                s_dac_chunk[i] = static_cast<uint16_t>((input[i] >> 16) ^ 0x8000);
            }
            output = reinterpret_cast<const uint8_t *>(s_dac_chunk);
            output_length = sample_count * sizeof(uint16_t);
        }

        esp_err_t write_error = ESP_OK;
        size_t offset = 0;
        while (offset < output_length) {
            size_t written = 0;
            write_error = i2s_write(I2S_PORT, output + offset, output_length - offset, &written, portMAX_DELAY);
            if (write_error != ESP_OK || written == 0) break;
            offset += written;
        }
        xSemaphoreGive(s_i2s_mutex);

        if (write_error != ESP_OK || offset != output_length) {
            Serial.printf("[Audio Engine] I2S write failed (%d), wrote %u of %u bytes.\n",
                write_error, static_cast<unsigned>(offset), static_cast<unsigned>(output_length));
            vTaskDelay(1);
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
    if (bytes_read < 0) return;
    command[bytes_read] = '\0';
    // Discard a datagram tail if it was larger than our bounded command buffer.
    while (controlUdp.available()) controlUdp.read();

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
        int rssi = WiFi.status() == WL_CONNECTED ? WiFi.RSSI() : -50;
        unsigned int fill_pct = (rb_available_read() * 100U) / RING_BUFFER_SIZE;
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
            applied = mode == getActiveMode() || configureI2SHardware(mode, getActiveSampleRate());
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
            applied = sample_rate == getActiveSampleRate() ||
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

    s_i2s_mutex = xSemaphoreCreateMutex();
    if (s_i2s_mutex == nullptr || !configureI2SHardware(MODE_INTERNAL_ESP32_DAC, 48000)) {
        Serial.println("[Audio Engine] Fatal: could not initialize the I2S output.");
        while (true) delay(1000);
    }

    // Keep I2S writes on one task so mode changes can safely stop at the mutex.
    BaseType_t task_created = xTaskCreatePinnedToCore(
        audioWorkerTask,
        "AudioDMAWorker",
        4096,
        NULL,
        configMAX_PRIORITIES - 1, // High priority real-time audio thread
        NULL,
        1                         // Core 1
    );
    if (task_created != pdPASS) {
        Serial.println("[Audio Engine] Fatal: could not start the I2S worker task.");
        while (true) delay(1000);
    }

    // Connect to Phone Hotspot
    Serial.print("[Wi-Fi] Connecting to Hotspot: ");
    Serial.println(HOTSPOT_SSID);

    WiFi.mode(WIFI_STA);
    WiFi.begin(HOTSPOT_SSID, HOTSPOT_PASS);
    WiFi.setSleep(false); // Eliminate Wi-Fi modem sleep power-rail ripple and latency jitter

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
            Serial.println("[Wi-Fi] Fatal: failed to start the standalone AP.");
            while (true) delay(1000);
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
// NETWORK RECEIVER LOOP
// ============================================================================
void loop() {
    handleControlUdp();

    WiFiClient client = server.available();
    if (client) {
        Serial.println("\n[Stream] >>> Phone connected! Streaming active.");
        client.setNoDelay(true);
        client.print("HIFI_CONNECTED MODE:");
        client.println(getActiveMode());

        size_t totalBytes = 0;
        unsigned long lastStat = millis();

        while (client.connected()) {
            handleControlUdp();

            int available = client.available();
            if (available > 0) {
                // Read incoming pure PCM audio chunks directly into pre-allocated buffer
                int toRead = min(available, (int)sizeof(s_rx_buf));
                size_t freeSpace = rb_available_write();
                toRead = min(toRead, (int)freeSpace);

                if (toRead > 0) {
                    int bytesRead = client.read(s_rx_buf, toRead);
                    if (bytesRead > 0) {
                        if (rb_write(s_rx_buf, bytesRead)) totalBytes += bytesRead;
                    }
                } else {
                    vTaskDelay(1);
                }
            } else {
                vTaskDelay(1);
            }

            if (millis() - lastStat > 3000) {
                if (totalBytes > 0) {
                    Serial.printf("[Stream] Active | Mode: %s | Total: %u KB | Buffer fill: %u%%\n",
                        getActiveMode() == MODE_EXTERNAL_PCM5102A ? "PCM5102A (24/96)" : "Internal DAC",
                        static_cast<unsigned int>(totalBytes / 1024),
                        static_cast<unsigned int>((rb_available_read() * 100) / RING_BUFFER_SIZE)
                    );
                }
                lastStat = millis();
            }
        }

        client.stop();
        if (xSemaphoreTake(s_i2s_mutex, portMAX_DELAY) == pdTRUE) {
            i2s_zero_dma_buffer(I2S_PORT);
            rb_reset();
            xSemaphoreGive(s_i2s_mutex);
        }
        Serial.println("[Stream] Phone disconnected.");
    }

    delay(10);
}
