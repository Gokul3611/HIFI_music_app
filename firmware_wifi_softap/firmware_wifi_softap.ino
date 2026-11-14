/**
 * ============================================================================
 * ESP32 Audiophile Wireless Audio Receiver (Dual-Mode Dynamic Switcher)
 * ============================================================================
 * 
 * Manual Memory Management: Zero runtime malloc/free in audio loop.
 * True SPSC Lockless 64 KB Circular DMA Ring Buffer.
 * Phone App Dynamic Switching: Switch between PCM5102A & Built-in DAC on-the-fly!
 * 
 * Supported Modes:
 *   MODE 1: External PCM5102A 32-bit I2S DAC (Bit-Perfect 24-bit / 96 kHz)
 *           ESP32 GPIO 26 -> BCK, GPIO 25 -> LCK, GPIO 22 -> DIN, GND -> SCK
 *   MODE 2: ESP32 Built-in DAC (Direct 3-Wire Connection to Amp)
 *           ESP32 GPIO 25 -> Amp Left (L+), GPIO 26 -> Amp Right (R+), GND -> Ground
 * ============================================================================
 */

#include <WiFi.h>
#include "driver/i2s.h"

// Wi-Fi Hotspot Credentials (matching Music App default)
const char* HOTSPOT_SSID = "Stash-Hotspot";
const char* HOTSPOT_PASS = "12345678";

const uint16_t TCP_PORT = 8888;
WiFiServer server(TCP_PORT);

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
static volatile uint32_t s_rb_head = 0; // Written only by Network Producer (Core 0)
static volatile uint32_t s_rb_tail = 0; // Read only by Audio Consumer (Core 1)

// Statically allocated DMA scratch buffers (Zero heap fragmentation)
#define DMA_CHUNK_SIZE           2048
static uint8_t s_dma_chunk[DMA_CHUNK_SIZE] __attribute__((aligned(4)));
static uint16_t s_dac_chunk[DMA_CHUNK_SIZE / 2] __attribute__((aligned(4)));
static uint8_t s_rx_buf[4096] __attribute__((aligned(4)));

// Active Hardware State
static volatile uint8_t s_active_mode = MODE_EXTERNAL_PCM5102A;
static volatile bool s_i2s_initialized = false;
static portMUX_TYPE s_mode_mux = portMUX_INITIALIZER_UNLOCKED;

// ============================================================================
// SPSC LOCKLESS RING BUFFER OPERATIONS
// ============================================================================
static inline size_t rb_available_write() {
    uint32_t head = s_rb_head;
    uint32_t tail = s_rb_tail;
    return RING_BUFFER_SIZE - 1 - ((head - tail) & RING_BUFFER_MASK);
}

static inline size_t rb_available_read() {
    uint32_t head = s_rb_head;
    uint32_t tail = s_rb_tail;
    return (head - tail) & RING_BUFFER_MASK;
}

static inline void rb_write(const uint8_t *data, size_t length) {
    uint32_t head = s_rb_head;
    for (size_t i = 0; i < length; i++) {
        s_ringbuffer[head] = data[i];
        head = (head + 1) & RING_BUFFER_MASK;
    }
    s_rb_head = head;
}

static inline void rb_read(uint8_t *dest, size_t length) {
    uint32_t tail = s_rb_tail;
    for (size_t i = 0; i < length; i++) {
        dest[i] = s_ringbuffer[tail];
        tail = (tail + 1) & RING_BUFFER_MASK;
    }
    s_rb_tail = tail;
}

static inline void rb_reset() {
    s_rb_tail = s_rb_head;
}

// ============================================================================
// DYNAMIC I2S HARDWARE CONTROLLER
// ============================================================================
void configureI2SHardware(uint8_t new_mode) {
    portENTER_CRITICAL(&s_mode_mux);

    if (s_i2s_initialized) {
        i2s_zero_dma_buffer(I2S_PORT);
        i2s_driver_uninstall(I2S_PORT);
        s_i2s_initialized = false;
    }

    if (new_mode == MODE_EXTERNAL_PCM5102A) {
        // Mode 1: External PCM5102A 32-bit slot I2S with Audiophile APLL Clock (96 kHz)
        i2s_config_t i2s_config = {
            .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX),
            .sample_rate = 96000,
            .bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT,
            .channel_format = I2S_CHANNEL_FMT_RIGHT_LEFT,
            .communication_format = I2S_COMM_FORMAT_STAND_I2S,
            .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
            .dma_buf_count = 8,
            .dma_buf_len = 512,
            .use_apll = true, // Low-jitter APLL clock
            .tx_desc_auto_clear = true
        };

        i2s_pin_config_t pin_config = {
            .bck_io_num = I2S_BCK_PIN,
            .ws_io_num = I2S_WS_PIN,
            .data_out_num = I2S_DATA_PIN,
            .data_in_num = I2S_PIN_NO_CHANGE
        };

        i2s_driver_install(I2S_PORT, &i2s_config, 0, NULL);
        i2s_set_pin(I2S_PORT, &pin_config);
        i2s_zero_dma_buffer(I2S_PORT);
        s_active_mode = MODE_EXTERNAL_PCM5102A;
        s_i2s_initialized = true;
        Serial.println("\n[Audio Engine] >>> Switched to EXTERNAL PCM5102A (24-bit / 96 kHz bit-perfect, APLL)");
    } else {
        // Mode 2: Built-in ESP32 Dual DAC (GPIO 25 Left, GPIO 26 Right directly to Amp)
        i2s_config_t i2s_config = {
            .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX | I2S_MODE_DAC_BUILT_IN),
            .sample_rate = 48000,
            .bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT,
            .channel_format = I2S_CHANNEL_FMT_RIGHT_LEFT,
            .communication_format = I2S_COMM_FORMAT_STAND_MSB,
            .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
            .dma_buf_count = 8,
            .dma_buf_len = 512,
            .use_apll = false,
            .tx_desc_auto_clear = true
        };

        i2s_driver_install(I2S_PORT, &i2s_config, 0, NULL);
        i2s_set_dac_mode(I2S_DAC_CHANNEL_BOTH_EN);
        i2s_zero_dma_buffer(I2S_PORT);
        s_active_mode = MODE_INTERNAL_ESP32_DAC;
        s_i2s_initialized = true;
        Serial.println("\n[Audio Engine] >>> Switched to BUILT-IN ESP32 DAC (GPIO 25 Left, GPIO 26 Right)");
    }

    rb_reset();
    portEXIT_CRITICAL(&s_mode_mux);
}

// ============================================================================
// REAL-TIME AUDIO DMA WORKER (CORE 1)
// ============================================================================
void audioWorkerTask(void *param) {
    while (true) {
        size_t available = rb_available_read();

        if (s_active_mode == MODE_EXTERNAL_PCM5102A) {
            // Mode 1: Bit-Perfect 32-bit DMA transfer (Zero modification)
            if (available >= DMA_CHUNK_SIZE) {
                rb_read(s_dma_chunk, DMA_CHUNK_SIZE);
                size_t written = 0;
                i2s_write(I2S_PORT, s_dma_chunk, DMA_CHUNK_SIZE, &written, portMAX_DELAY);
            } else {
                vTaskDelay(1 / portTICK_PERIOD_MS);
            }
        } else {
            // Mode 2: Internal DAC (High-speed manual integer conversion from 32-bit signed to 16-bit offset-binary)
            const size_t needed = 2048; // 512 stereo samples (4 bytes each)
            if (available >= needed) {
                rb_read(s_dma_chunk, needed);
                int32_t *in32 = (int32_t *)s_dma_chunk;
                int numSamples = needed / 4;

                for (int i = 0; i < numSamples; i++) {
                    // Manual bit manipulation: take high 16 bits, invert sign bit for unsigned R-2R DAC
                    s_dac_chunk[i] = (uint16_t)((in32[i] >> 16) ^ 0x8000);
                }

                size_t written = 0;
                i2s_write(I2S_PORT, s_dac_chunk, numSamples * sizeof(uint16_t), &written, portMAX_DELAY);
            } else {
                vTaskDelay(1 / portTICK_PERIOD_MS);
            }
        }
    }
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

    // Initialize with default mode (PCM5102A)
    configureI2SHardware(MODE_EXTERNAL_PCM5102A);

    // Launch Audio DMA Task pinned to Core 1 (Isolated from Wi-Fi stack on Core 0)
    xTaskCreatePinnedToCore(
        audioWorkerTask,
        "AudioDMAWorker",
        4096,
        NULL,
        configMAX_PRIORITIES - 1, // High priority real-time audio thread
        NULL,
        1                         // Core 1
    );

    // Connect to Phone Hotspot
    Serial.print("[Wi-Fi] Connecting to Hotspot: ");
    Serial.println(HOTSPOT_SSID);

    WiFi.mode(WIFI_STA);
    WiFi.begin(HOTSPOT_SSID, HOTSPOT_PASS);

    unsigned long startAttempt = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - startAttempt < 10000) {
        delay(300);
        Serial.print(".");
    }

    if (WiFi.status() == WL_CONNECTED) {
        Serial.println("\n[Wi-Fi] Connected to Phone Hotspot successfully!");
        Serial.print("[Wi-Fi] ESP32 IP Address: ");
        Serial.println(WiFi.localIP());
    } else {
        // Fallback: Standalone AP if phone hotspot is not found
        Serial.println("\n[Wi-Fi] Hotspot not found. Starting Standalone AP mode...");
        WiFi.mode(WIFI_AP);
        WiFi.softAP("ESP32-HiFi-Hotspot", "12345678");
        Serial.print("[Wi-Fi] AP Started. ESP32 IP: ");
        Serial.println(WiFi.softAPIP());
    }

    server.begin();
    server.setNoDelay(true);
    Serial.printf("[Server] TCP Audio Server listening on port %d...\n", TCP_PORT);
    Serial.println("[Ready] In the Music app: Select 'ESP32 Hi-Fi Direct' and pick your DAC mode!");
}

// ============================================================================
// NETWORK RECEIVER LOOP (CORE 0)
// ============================================================================
void loop() {
    WiFiClient client = server.available();
    if (client) {
        Serial.println("\n[Stream] >>> Phone connected! Streaming active.");
        client.setNoDelay(true);

        size_t totalBytes = 0;
        unsigned long lastStat = millis();

        while (client.connected()) {
            int available = client.available();
            if (available > 0) {
                // Check if this is an 8-byte control packet: 'H','I','F','I', mode, 0, 0, 0
                if (available >= 8 && client.peek() == 'H') {
                    uint8_t probe[4];
                    client.peekBytes((char*)probe, 4);
                    if (probe[0] == 'H' && probe[1] == 'I' && probe[2] == 'F' && probe[3] == 'I') {
                        uint8_t ctrl[8];
                        client.read(ctrl, 8);
                        uint8_t new_mode = ctrl[4];
                        if (new_mode == MODE_EXTERNAL_PCM5102A || new_mode == MODE_INTERNAL_ESP32_DAC) {
                            if (new_mode != s_active_mode) {
                                configureI2SHardware(new_mode);
                            }
                        }
                        continue;
                    }
                }

                // Read incoming audio chunks into pre-allocated buffer
                int toRead = min(available, (int)sizeof(s_rx_buf));
                size_t freeSpace = rb_available_write();
                toRead = min(toRead, (int)freeSpace);

                if (toRead > 0) {
                    int bytesRead = client.read(s_rx_buf, toRead);
                    if (bytesRead > 0) {
                        rb_write(s_rx_buf, bytesRead);
                        totalBytes += bytesRead;
                    }
                } else {
                    vTaskDelay(1 / portTICK_PERIOD_MS);
                }
            } else {
                vTaskDelay(1 / portTICK_PERIOD_MS);
            }

            if (millis() - lastStat > 3000) {
                if (totalBytes > 0) {
                    Serial.printf("[Stream] Active | Mode: %s | Total: %u KB | Buffer fill: %u%%\n",
                        s_active_mode == MODE_EXTERNAL_PCM5102A ? "PCM5102A (24/96)" : "Internal DAC",
                        totalBytes / 1024,
                        (rb_available_read() * 100) / RING_BUFFER_SIZE
                    );
                }
                lastStat = millis();
            }
        }

        client.stop();
        rb_reset();
        Serial.println("[Stream] Phone disconnected.");
    }

    delay(10);
}
