# 📡 ESP32 + PCM5102A Hi-Fi Connection & Setup Guide

This document provides complete, step-by-step instructions to wire, flash, configure, and connect your **ESP32** and **PCM5102A 32-bit DAC** with the **Music** Android application for bit-perfect 24-bit / 96 kHz wireless streaming.

---

## 🧰 Required Components (~₹300 / $3.60 Total)

1. **ESP32 Development Board** (ESP32-WROOM-32 or ESP32-WROVER, 30-pin or 38-pin).
2. **PCM5102A I2S 32-bit / 384 kHz DAC Breakout Board** (typically purple or yellow PCB).
3. **5× Female-to-Female Jumper Wires**.
4. **3.5mm Stereo AUX Cable or 3-pin Audio Cable** to connect the DAC to your amplifier.
5. **Micro-USB Cable & 5V Power Supply** (or phone charger / power bank).

---

## 🔌 1. Hardware Pinout & Wiring

You can choose between **two hardware setup modes**:

### Mode 1: External PCM5102A 32-bit DAC (Recommended for Audiophile Hi-Fi)
Provides true **24-bit / 96 kHz studio master resolution** with 112 dB dynamic range.

```
+--------------------+                     +--------------------+
|    ESP32 BOARD     |                     |   PCM5102A DAC     |
|                    |                     |                    |
|                VIN | ------------------> | VCC                |
|                GND | ------------------> | GND                |
|            GPIO 27 | ------------------> | BCK (Bit Clock)    |
|            GPIO 14 | ------------------> | LCK (Word Select)  |
|            GPIO 22 | ------------------> | DIN (Data In)      |
|                GND | ------------------> | SCK (System Clock) |
+--------------------+                     +--------------------+
                                                     |
                                                     v
                                            [3.5mm AUX / Line Out]
                                                     |
                                                     v
                                          [Analog Stereo Amplifier]
```

| ESP32 Pin | PCM5102A Pin | Wire Function | Details & Notes |
| :--- | :--- | :--- | :--- |
| **VIN / 5V** | **VCC** | Power Supply | Connect to 5V (VIN) for maximum analog headroom (2.1V RMS line level). 3.3V also works. |
| **GND** | **GND** | Power Ground | Common ground between ESP32 and DAC. |
| **GPIO 27** | **BCK** | I2S Bit Clock | Dedicated bit clock (independent of internal DAC to prevent RF/square wave crosstalk). |
| **GPIO 14** | **LCK / LRCK** | I2S Word Select | Dedicated word select / frame clock. |
| **GPIO 22** | **DIN / DATA** | I2S Audio Data | Carries 32-bit serial PCM audio samples. |
| **GND** | **SCK** | Master Clock Ground | **CRITICAL:** SCK must be connected to GND so the PCM5102A's internal low-jitter PLL generates its own master clock. |

---

### Mode 2: ESP32 Built-in Internal DAC (Direct 3-Wire Connection to Amp)
No external DAC board needed! Uses the ESP32's built-in dual-channel hardware DAC directly connected to your amplifier's AUX / RCA input.

```
+--------------------+                     +---------------------------+
|    ESP32 BOARD     |                     |  ANALOG STEREO AMPLIFIER  |
|                    |                     |                           |
|    GPIO 25 (DAC 1) | ------------------> | Left Channel Input (L+)   |
|    GPIO 26 (DAC 2) | ------------------> | Right Channel Input (R+)  |
|                GND | ------------------> | Audio Ground (GND / L-/R-)|
+--------------------+                     +---------------------------+
```

| ESP32 Pin | Amplifier Input Pin | Description |
| :--- | :--- | :--- |
| **GPIO 25** | **Left (L+)** | Built-in DAC Channel 1 (Left Stereo Channel) |
| **GPIO 26** | **Right (R+)** | Built-in DAC Channel 2 (Right Stereo Channel) |
| **GND** | **Ground (GND / L- / R-)** | Common Audio Ground |

> **To activate Mode 2:** In `firmware_wifi_softap.ino`, change line 33 to:  
> `#define AUDIO_OUTPUT_MODE MODE_INTERNAL_ESP32_DAC`

---

## 🎚️ 2. PCM5102A Solder Jumpers (Board Defaults)

Inspect the underside of your PCM5102A breakout board. Most boards arrive pre-configured with solder bridges, but verify:

| Jumper Pad | Name | Setting | Function |
| :--- | :--- | :--- | :--- |
| **H1L** | FLT | **Low (Bridged to GND)** | Low-latency FIR digital filter. |
| **H2L** | DEMP | **Low (Bridged to GND)** | De-emphasis filter OFF (required for bit-perfect audio). |
| **H3L** | XSM | **High (Bridged to VCC/3V3)** | Soft-mute disabled (audio output enabled). |
| **H4L** | FMT | **Low (Bridged to GND)** | Standard I2S audio format (not Left-Justified). |

---

## 🔊 3. Amplifier Audio Output Connection

The PCM5102A board provides two output options:
1. **3.5mm Stereo Jack:** Plug a standard 3.5mm-to-RCA or 3.5mm AUX cable directly from the DAC into your stereo amplifier's AUX/LINE IN.
2. **3-Pin Header (L, G, R):**
   - **`L`** $\to$ Left Channel Signal (`L+`)
   - **`G`** $\to$ Audio Ground (`L-` and `R-` common ground)
   - **`R`** $\to$ Right Channel Signal (`R+`)

---

## 💻 4. Flashing the ESP32 Firmware

### Method: Arduino IDE (Easiest)
1. Download and install [Arduino IDE 2.x](https://www.arduino.cc/en/software).
2. Install the ESP32 board package:
   - Go to **File $\to$ Preferences**.
   - In *Additional Board Manager URLs*, paste:  
     `https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json`
   - Open **Tools $\to$ Board $\to$ Boards Manager**, search for `esp32` by Espressif Systems, and click **Install**.
3. Open the sketch in this repository:
   - File path: [`firmware_wifi_softap/firmware_wifi_softap.ino`](firmware_wifi_softap/firmware_wifi_softap.ino)
4. Connect your ESP32 to your PC via USB.
5. In Arduino IDE:
   - **Tools $\to$ Board:** Select **ESP32 Dev Module** (or your specific ESP32 variant).
   - **Tools $\to$ Port:** Select the COM port of your ESP32.
   - Click the **Upload (➡️)** button.
6. Open **Tools $\to$ Serial Monitor** and set the baud rate to **115200**.

---

## 📱 5. Phone Hotspot Configuration (Crucial Step)

The ESP32 communicates over Wi-Fi directly with your phone. To ensure reliable, zero-latency streaming:

1. Open your Android phone's **Settings $\to$ Portable Hotspot** (or Personal Hotspot).
2. Configure your hotspot:
   - **Hotspot Name (SSID):** `FBI-VAN` (or set matching SSID in firmware)
   - **Security:** `WPA2 PSK`
   - **Password:** `87654321` (or matching password)
   - **AP Band:** **2.4 GHz Band**
   
> [!IMPORTANT]  
> The ESP32-WROOM-32 only supports **2.4 GHz Wi-Fi**. If your phone's hotspot is configured to *5 GHz Band* or *Wi-Fi 6 Only*, the ESP32 will not be able to detect it. Ensure your hotspot band is set to **2.4 GHz** (or enable **Maximized Compatibility** on Samsung/OnePlus/Pixel devices).

3. Turn **ON** your phone's Personal Hotspot.
4. Power on your ESP32.
5. Within 3–5 seconds, the ESP32 will connect. Check your phone's **Connected Devices** list under Hotspot settings:
   - You will see a device (e.g., `Espressif` or `IP: 192.168.43.1` or `192.168.43.x`).

---

## 🎶 6. Connecting & Streaming from the Music App

1. Open the **Music** app on your phone.
2. Tap the **Settings** icon (bottom navigation or side drawer).
3. Tap **Audio & Quality** $\to$ **Output** $\to$ **Hi-Res Output Mode**.
4. Select **ESP32 Hi-Fi Direct**.
5. The **ESP32 Hi-Fi Hotspot Guide** dialog will appear:
   - Verify or enter the ESP32 IP address shown in your hotspot list (default is `192.168.43.1`).
   - Tap **Connect & Enable**.
6. Play any track (online or your downloaded 24-bit/96 kHz FLAC files):
   - **Your phone's speaker and headphones are completely muted.**
   - The uncompressed, bit-perfect 24-bit / 96 kHz PCM stream is transmitted over Wi-Fi directly to the PCM5102A DAC and output to your amplifier!

---

## 🛠️ Troubleshooting & FAQ

### Q1: The ESP32 does not connect to my hotspot.
- **Check Band:** Ensure your hotspot is transmitting on **2.4 GHz**, not 5 GHz.
- **Check Credentials:** The SSID must be exactly `Stash-Hotspot` (case-sensitive) and password `12345678`.
- **Serial Monitor:** Open Serial Monitor at 115200 baud to see connection status output.

### Q2: There is no sound coming from the amplifier.
- **Check SCK pin:** Ensure `SCK` on the PCM5102A is connected to `GND`. Without SCK grounded, the internal PLL will not generate the master audio clock and the DAC will output silence.
- **Check IP Address:** Verify that the IP address entered in the app matches the ESP32's current IP address on your hotspot.
- **Check Volume:** Ensure your analog amplifier volume is turned up and the correct input channel (AUX/LINE IN) is selected.

### Q3: Sound has buzzing or background hum.
- **Power Ground Loop:** If the ESP32 is powered from the same USB port as the amplifier, ground loop noise can occur. Power the ESP32 using an independent 5V phone charger or power bank.
- **Loose Jumpers:** Ensure the `GND`, `BCK`, `DIN`, and `LCK` jumper wires are firmly seated.

### Q4: Can I stream audio without mobile data?
- **Yes!** The personal hotspot does not require active internet or mobile data. The phone and ESP32 communicate entirely over local Wi-Fi. You can play your offline downloaded FLAC/MP3 files anywhere (in a car, outdoors, or in a studio).

