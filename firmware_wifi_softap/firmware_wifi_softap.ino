/**
 * ============================================================================
 * ESP32 + PCM5102A Audiophile Wi-Fi Direct Receiver (Arduino IDE Compatible)
 * ============================================================================
 * 
 * Bit-Perfect 24-bit / 96 kHz (32-bit slot) PCM Receiver for Music App
 * 
 * Hardware Wiring:
 *   ESP32 Pin     PCM5102A Pin
 *   ---------------------------
 *   VIN / 5V ---> VCC
 *   GND      ---> GND
 *   GPIO 26  ---> BCK (Bit Clock)
 *   GPIO 25  ---> LRCK / LCK (Left/Right Word Select)
 *   GPIO 22  ---> DIN / DATA (Audio Data)
 *   GND      ---> SCK (Tie to GND for internal PLL mode)
 * ============================================================================
 */

#include <WiFi.h>
#include "driver/i2s.h"

// 1. Phone Hotspot Credentials (matching Music App default)
const char* HOTSPOT_SSID = "Stash-Hotspot";
const char* HOTSPOT_PASS = "12345678";

// TCP Streaming Port
const uint16_t TCP_PORT = 8888;
WiFiServer server(TCP_PORT);

// I2S Pins
#define I2S_BCK_PIN   26
#define I2S_WS_PIN    25
#define I2S_DATA_PIN  22

// Audio Configuration: 96 kHz / 32-bit slot (for 24-bit bit-perfect dynamic range)
#define SAMPLE_RATE   96000
#define I2S_PORT      I2S_NUM_0

// Fast DMA RX buffer
static uint8_t rxBuffer[4096];

void initI2S() {
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
}

void setup() {
    Serial.begin(115200);
    delay(500);

    Serial.println("\n==================================================");
    Serial.println("  ESP32 Hi-Fi Direct Receiver (96kHz / 24-bit)");
    Serial.println("==================================================");

    initI2S();
    Serial.println("[I2S] Hardware initialized: 96000 Hz, 32-bit slot, APLL enabled.");

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
        // Fallback: Start SoftAP if phone hotspot is not found
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
        Serial.println("\n[Stream] >>> Phone connected! Receiving bit-perfect PCM stream...");
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
                    i2s_write(I2S_PORT, rxBuffer, bytesRead, &bytesWritten, portMAX_DELAY);
                    totalBytes += bytesWritten;
                }
            } else {
                delay(1);
            }

            if (millis() - lastStat > 3000) {
                if (totalBytes > 0) {
                    Serial.printf("[Stream] Streaming @ 96kHz / 32-bit | Total received: %u KB\n", totalBytes / 1024);
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
