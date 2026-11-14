/**
 * ============================================================================
 * ESP32 Wi-Fi Direct Audio Receiver (Arduino IDE Compatible)
 * Dual-Mode: External PCM5102A DAC (Hi-Fi 96kHz) OR Internal ESP32 DAC
 * ============================================================================
 * 
 * MODE 1: EXTERNAL PCM5102A 32-bit I2S DAC (Recommended for Audiophile Hi-Fi)
 *   ESP32 Pin     PCM5102A Pin
 *   ---------------------------
 *   VIN / 5V ---> VCC
 *   GND      ---> GND
 *   GPIO 26  ---> BCK (Bit Clock)
 *   GPIO 25  ---> LRCK / LCK (Left/Right Word Select)
 *   GPIO 22  ---> DIN / DATA (Audio Data)
 *   GND      ---> SCK (Tie to GND for internal PLL mode)
 *   PCM5102A 3.5mm / L-G-R ---> Amplifier AUX / Line In
 * 
 * MODE 2: ESP32 BUILT-IN INTERNAL DAC (Direct 3-Wire Connection to Amp)
 *   No external DAC board needed!
 *   ESP32 Pin     Amplifier Input
 *   -----------------------------
 *   GPIO 25  ---> Left Channel (L+)
 *   GPIO 26  ---> Right Channel (R+)
 *   GND      ---> Audio Ground (GND / L- / R-)
 * ============================================================================
 */

#include <WiFi.h>
#include "driver/i2s.h"

// ============================================================================
// 1. SELECT YOUR AUDIO OUTPUT MODE:
// ============================================================================
#define MODE_EXTERNAL_PCM5102A   1   // 32-bit / 96 kHz Audiophile External DAC
#define MODE_INTERNAL_ESP32_DAC  2   // Built-in ESP32 DAC (3-wire direct to Amp)

// Choose which mode to use (change to MODE_INTERNAL_ESP32_DAC if using built-in DAC):
#define AUDIO_OUTPUT_MODE        MODE_EXTERNAL_PCM5102A

// ============================================================================
// 2. Wi-Fi Hotspot Credentials (matching Music App default)
// ============================================================================
const char* HOTSPOT_SSID = "Stash-Hotspot";
const char* HOTSPOT_PASS = "12345678";

// TCP Streaming Port
const uint16_t TCP_PORT = 8888;
WiFiServer server(TCP_PORT);

// I2S Configuration
#define I2S_PORT      I2S_NUM_0

#if AUDIO_OUTPUT_MODE == MODE_EXTERNAL_PCM5102A
  #define SAMPLE_RATE   96000
  #define I2S_BCK_PIN   26
  #define I2S_WS_PIN    25
  #define I2S_DATA_PIN  22
#else
  #define SAMPLE_RATE   48000
  // Internal DAC uses GPIO 25 (DAC1 - Left) and GPIO 26 (DAC2 - Right) automatically
#endif

// Fast DMA RX buffer
static uint8_t rxBuffer[4096];
#if AUDIO_OUTPUT_MODE == MODE_INTERNAL_ESP32_DAC
static uint16_t dacBuffer[1024];
#endif

void initAudio() {
#if AUDIO_OUTPUT_MODE == MODE_EXTERNAL_PCM5102A
    // Mode 1: External PCM5102A 32-bit slot I2S with Audiophile APLL Clock
    i2s_config_t i2s_config = {
        .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX),
        .sample_rate = SAMPLE_RATE,
        .bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT,
        .channel_format = I2S_CHANNEL_FMT_RIGHT_LEFT,
        .communication_format = I2S_COMM_FORMAT_STAND_I2S,
        .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
        .dma_buf_count = 8,
        .dma_buf_len = 512,
        .use_apll = true, // Audiophile Low-Jitter Audio PLL
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
    Serial.println("[Audio] Mode: EXTERNAL PCM5102A DAC (96 kHz, 32-bit slot, APLL clock)");
#else
    // Mode 2: ESP32 Built-in DAC (GPIO 25 & GPIO 26 direct to Amp)
    i2s_config_t i2s_config = {
        .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX | I2S_MODE_DAC_BUILT_IN),
        .sample_rate = SAMPLE_RATE,
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
    Serial.println("[Audio] Mode: BUILT-IN ESP32 DAC (GPIO 25=Left, GPIO 26=Right, direct to Amp)");
#endif
}

void setup() {
    Serial.begin(115200);
    delay(500);

    Serial.println("\n==================================================");
    Serial.println("     ESP32 Hi-Fi Wireless Audio Receiver          ");
    Serial.println("==================================================");

    initAudio();

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
        // Fallback: Start SoftAP if phone hotspot is not detected
        Serial.println("\n[Wi-Fi] Hotspot not found. Starting Standalone AP mode...");
        WiFi.mode(WIFI_AP);
        WiFi.softAP("ESP32-HiFi-Hotspot", "12345678");
        Serial.print("[Wi-Fi] AP Started. ESP32 IP: ");
        Serial.println(WiFi.softAPIP());
    }

    server.begin();
    server.setNoDelay(true);
    Serial.printf("[Server] TCP Audio Server listening on port %d...\n", TCP_PORT);
    Serial.println("[Ready] In the Music app: Select 'ESP32 Hi-Fi Direct' and enter the ESP32 IP!");
}

void loop() {
    WiFiClient client = server.available();
    if (client) {
        Serial.println("\n[Stream] >>> Phone connected! Receiving audio stream...");
        client.setNoDelay(true);

        size_t totalBytes = 0;
        unsigned long lastStat = millis();

        while (client.connected()) {
            int available = client.available();
            if (available > 0) {
                int toRead = min(available, (int)sizeof(rxBuffer));
                int bytesRead = client.read(rxBuffer, toRead);
                if (bytesRead > 0) {
                    size_t bytesWritten = 0;
#if AUDIO_OUTPUT_MODE == MODE_EXTERNAL_PCM5102A
                    // Write 32-bit PCM frames directly to PCM5102A DMA
                    i2s_write(I2S_PORT, rxBuffer, bytesRead, &bytesWritten, portMAX_DELAY);
                    totalBytes += bytesWritten;
#else
                    // Convert 32-bit signed PCM to 16-bit unsigned offset for internal DAC
                    int numSamples = bytesRead / 4;
                    int32_t* in32 = (int32_t*)rxBuffer;
                    for (int i = 0; i < numSamples; i++) {
                        // High 16 bits with sign bit inverted for unsigned internal DAC
                        dacBuffer[i] = (uint16_t)((in32[i] >> 16) ^ 0x8000);
                    }
                    i2s_write(I2S_PORT, dacBuffer, numSamples * sizeof(uint16_t), &bytesWritten, portMAX_DELAY);
                    totalBytes += (numSamples * 2);
#endif
                }
            } else {
                delay(1);
            }

            if (millis() - lastStat > 3000) {
                if (totalBytes > 0) {
                    Serial.printf("[Stream] Active | Total received: %u KB\n", totalBytes / 1024);
                }
                lastStat = millis();
            }
        }

        client.stop();
        i2s_zero_dma_buffer(I2S_PORT);
        Serial.println("[Stream] Phone disconnected.");
    }

    delay(10);
}
