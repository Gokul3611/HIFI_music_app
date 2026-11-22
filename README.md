# 🎵 ESP32 Audiophile Wireless Audio Receiver & Hi-Fi DAC System
### Bit-Perfect 24-bit / 192 kHz Wireless Streaming with Dynamic Dual-Mode DAC Switching

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-ESP32%20WROOM--32-red.svg)](https://www.espressif.com/)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026--35)-green.svg)](https://developer.android.com/)
[![Audio](https://img.shields.io/badge/Audio-24--bit%20%7C%20192%20kHz%20PCM-gold.svg)](#)

A high-performance, low-latency wireless audio streaming system connecting the **Music** Android client to an **ESP32** receiver node over a dedicated local Wi-Fi TCP socket. Bypasses the lossy Android `AudioFlinger` mixer, OS sample-rate conversion, and Bluetooth compression (SBC/AAC) to stream bit-perfect linear PCM directly into studio-grade hardware DACs.

---

## 📑 Table of Contents

- [Key Highlights & Architecture](#-key-highlights--architecture)
- [Hardware Pinout & Wiring](#-hardware-pinout--wiring)
  - [Mode 1: External TI PCM5102A 32-Bit DAC](#mode-1-external-ti-pcm5102a-32-bit-dac-recommended)
  - [Mode 2: ESP32 Built-in 8-Bit DAC (Zero-Cost)](#mode-2-esp32-built-in-8-bit-dac-zero-cost)
- [Firmware Concurrency & Audio Engine](#-firmware-concurrency--audio-engine)
- [Network Protocol & Control Interface](#-network-protocol--control-interface)
  - [TCP Audio Streaming (Port 8888)](#1-tcp-audio-stream-port-8888)
  - [Out-of-Band UDP Control (Port 8889)](#2-out-of-band-udp-control-port-8889)
  - [mDNS Auto-Discovery](#3-mdns-auto-discovery)
- [Setup & Flashing Guide](#-setup--flashing-guide)
- [Connecting with the Android Music App](#-connecting-with-the-android-music-app)
- [Troubleshooting & Diagnostics](#-troubleshooting--diagnostics)
- [Credits & Acknowledgements](#-credits--acknowledgements)
- [License](#-license)

---

## ⚡ Key Highlights & Architecture

```text
+---------------------------------------------------------------------------------------+
|                                    ANDROID CLIENT                                     |
|                                                                                       |
|  [ Lossless FLAC / High-Res Stream ] ---> [ Media3 / ExoPlayer Bitstream Decoder ]     |
|                                                        |                              |
|                                       [ Bit-Perfect PCM Extractor: S32_LE Interleaved]|
|                                                        |                              |
|                                                        v                              |
|                                         [ Esp32SocketStreamer Client ]                |
|                                         (Port 8888 Raw TCP Streaming)                 |
+--------------------------------------------------------|------------------------------+
                                                         | IEEE 802.11n Wi-Fi Hotspot
                                                         | TCP Stream (Up to 12.3 Mbps)
                                                         v
+---------------------------------------------------------------------------------------+
|                                 ESP32 RECEIVER & DAC                                  |
|                                                                                       |
|  [ FreeRTOS TCP Receiver (Core 1, Pri 1) ]                                            |
|                    |                                                                  |
|                    v (Atomic Write via 2-Segment memcpy)                              |
|  [ SPSC Circular Ring Buffer (64 KB Internal SRAM, 8-Byte Frame Aligned) ]             |
|                    |                                                                  |
|                    v (Atomic Read / Dynamic Prebuffer Watermark)                      |
|  [ Audio DMA Worker Task (Core 1, Pri 12) ] <--- Handshake (Pause/Resume Semaphores)  |
|                    |                                                                  |
|         +----------+----------+                                                       |
|         |                     |                                                       |
|         v                     v                                                       |
|   [ Mode 1: I2S0 APLL ]    [ Mode 2: Built-in DAC ]                                  |
|   (GPIO 27, 14, 22)        (GPIO 25, 26 with TPDF Dither & 1.65V DC Bias)             |
|         |                     |                                                       |
|         v                     v                                                       |
|  [ TI PCM5102A DAC ]   [ Direct Analog Out ]                                          |
|         |                     |                                                       |
|         +----------+----------+                                                       |
|                    |                                                                  |
|                    v                                                                  |
|         [ Analog Stereo Amplifier ]                                                   |
+---------------------------------------------------------------------------------------+
```

* **Dynamic Dual-Mode DAC Switching:** Switch on-the-fly between an external 32-bit I2S DAC (PCM5102A) and the ESP32’s built-in DAC directly from the phone app without rebooting.
* **Lock-Free SPSC 64 KB Circular Audio Ring Buffer:** Sized in 32-bit internal SRAM ($2^{16}$ power-of-2) with atomic acquire/release semantics and optimized 2-segment `memcpy` operations.
* **Starvation-Free Handshake:** Eliminates FreeRTOS priority inversion and mutex starvation between the real-time audio task (Priority 12) and the network receiver loop (Priority 1) via binary semaphores.
* **Frame-Aligned Stream Resets:** Preserves 8-byte frame boundaries (`& ~7u`) on reconnects or mode switches, preventing L/R channel swaps or bit-shifted static.
* **Pop-Free DC Silence:** Uses `0x8000` (1.65 V DC mid-rail) for internal DAC underruns and stops, eliminating violent 0 V step-transient speaker thumps.
* **1-LSB TPDF Dithering:** Linearizes the ESP32 built-in 8-bit DAC with triangular dither and symmetric `+128` rounding, eliminating harmonic quantization distortion.
* **Dedicated Hardware Pin Routing:** Separates external I2S lines (GPIO 27/14/22) from internal DAC lines (GPIO 25/26), protecting analog amplifier inputs from MHz digital square waves.

---

## 🔌 Hardware Pinout & Wiring

### Mode 1: External TI PCM5102A 32-Bit DAC (Recommended)

Delivers studio master quality (up to **24-bit / 192 kHz**) with 112 dB dynamic range.

```text
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

| ESP32 Pin | PCM5102A Pin | Signal | Details & Notes |
| :--- | :--- | :--- | :--- |
| **VIN (5V)** | **VCC** | Power Rail | 5V from USB/VIN provides maximum analog headroom (2.1V RMS line level). 3.3V also supported. |
| **GND** | **GND** | Power Ground | Common ground plane between ESP32 and DAC. |
| **GPIO 27** | **BCK** | I2S Bit Clock | Serial bit clock (dedicated pin, isolated from analog lines). |
| **GPIO 14** | **LCK / WS** | I2S Word Select | Left / Right audio channel framing clock. |
| **GPIO 22** | **DIN / DATA**| I2S Serial Data | Serial linear PCM audio stream. |
| **GND** | **SCK** | Master Clock | **CRITICAL:** Tie SCK to GND to engage the PCM5102A's internal low-jitter PLL. |

#### PCM5102A Solder Jumper Configuration (Board Underside)
* **`FLT`  $\to$ GND:** Low-latency FIR digital filter.
* **`DEMP` $\to$ GND:** De-emphasis filter disabled (required for bit-perfect audio).
* **`XSMT` $\to$ 3.3V / VCC:** Soft un-mute enabled (audio output active).
* **`FMT`  $\to$ GND:** Standard Philips I2S format.

---

### Mode 2: ESP32 Built-in 8-Bit DAC (Zero-Cost)

No extra breakout board needed. Directly drives an amplifier's line input using the ESP32’s internal dual-channel hardware DAC with 1-LSB TPDF dither and mid-scale bias.

```text
+--------------------+                     +---------------------------+
|    ESP32 BOARD     |                     |  ANALOG STEREO AMPLIFIER  |
|                    |                     |                           |
|    GPIO 25 (DAC 1) | ------------------> | Left Channel Input (L+)   |
|    GPIO 26 (DAC 2) | ------------------> | Right Channel Input (R+)  |
|                GND | ------------------> | Audio Ground (GND / L-/R-)|
+--------------------+                     +---------------------------+
```

| ESP32 Pin | Amplifier Input | Description |
| :--- | :--- | :--- |
| **GPIO 25** | **Left (L+)** | Built-in DAC Channel 1 (Left Stereo Channel) |
| **GPIO 26** | **Right (R+)** | Built-in DAC Channel 2 (Right Stereo Channel) |
| **GND** | **Ground (GND)** | Common Audio Ground Reference |

---

## 🛠️ Firmware Concurrency & Audio Engine

The firmware (`firmware_wifi_softap.ino`) implements several real-time embedded systems principles:

1. **Starvation-Free Handshake:**
   The audio worker runs on Core 1 at Priority 12, while the network receiver loop runs on Core 1 at Priority 1. Instead of contending on a FreeRTOS mutex inside the audio hot loop, `audioWorkerTask` checks an atomic `s_worker_pause_requested` flag on each iteration and parks itself on a binary semaphore. This gives `loop()` instantaneous exclusive control to change modes or sample rates without blocking or dropping packets.

2. **Sample-Rate Adaptive Prebuffering:**
   The prebuffer watermark adapts dynamically to the active sample rate:
   $$\text{Watermark} = \frac{\text{SampleRate} \times 8 \times 40}{1000} \text{ bytes}$$
   Clamped between 4,096 B and 32,768 B, this maintains a steady ~40 ms anti-jitter cushion from 8 kHz up to 192 kHz.

3. **Sample-Rate Scaled I2S Timeouts:**
   At low sample rates (e.g. 8 kHz), a single 480-frame DMA buffer takes 60 ms to drain. The write timeout scales dynamically:
   $$\text{Timeout (ms)} = \max\left(100, \frac{480 \times 2000}{\text{SampleRate}}\right)$$
   This prevents premature buffer dropouts at low sample rates.

4. **Symmetric 1-LSB TPDF Dither:**
   Eliminates quantization distortion on the internal DAC:
   ```cpp
   uint32_t r = fast_rand32();
   int32_t dither = static_cast<int32_t>(r >> 24) - static_cast<int32_t>((r >> 16) & 0xFF);
   int32_t val = (input[i] >> 16) + dither + 32768 + 128; // +128 symmetric rounding
   if (val < 0) val = 0; else if (val > 65535) val = 65535;
   s_dac_chunk[i ^ 1] = static_cast<uint16_t>(val & 0xFF00); // i ^ 1 corrects FIFO halfword swap
   ```

5. **SRAM Optimization:**
   Static BSS buffers are sized to fit comfortably inside the ESP32’s `.dram0.bss` memory segment with over 3.1 KB of headroom under Arduino-ESP32 3.x.

---

## 🌐 Network Protocol & Control Interface

### 1. TCP Audio Stream (Port 8888)
* **Transport:** Raw, uncompressed streaming TCP socket (`AF_INET`, `SOCK_STREAM`).
* **Format:** Signed 32-bit little-endian interleaved stereo PCM (`S32_LE`, 8 bytes per frame).
* **Flow Control:** Managed via TCP window backpressure paced by the hardware I2S clock.
* **Client Preemption:** `server.hasClient()` checks instantly disconnect dead or abruptly severed sockets if a new client reconnects.
* **Inactivity Protection:** 30-second idle timeout drops half-open connections while allowing paused playback.

### 2. Out-of-Band UDP Control (Port 8889)
Allows bidirectional control without injecting latency or headers into the audio bitstream:

| Command | Direction | Response | Description |
| :--- | :--- | :--- | :--- |
| `HIFI_DISCOVER` | App $\to$ ESP32 | `HIFI_DAC_HERE MODE:<1\|2>` | Broadcast discovery query. |
| `GET_STATUS` | App $\to$ ESP32 | `HIFI_STATUS:MODE:<m>,RATE:<r>,RSSI:<rssi>,BUFFER:<pct>%` | Telemetry query. |
| `MODE:<1\|2>` | App $\to$ ESP32 | `MODE_ACK:<mode>` / `MODE_NACK:<mode>` | Switches between External PCM5102A (1) and Internal DAC (2). |
| `RATE:<sample_rate>` | App $\to$ ESP32 | `RATE_ACK:<rate>` / `RATE_NACK:<rate>` | Switches sample rate (e.g. 44100, 48000, 96000, 192000). |

### 3. mDNS Auto-Discovery
The firmware broadcasts an mDNS service:
* **Hostname:** `music-dac.local`
* **Service:** `_audio-hifi._tcp` on Port 8888

---

## 💻 Setup & Flashing Guide

### Requirements
* [Arduino IDE 2.x](https://www.arduino.cc/en/software) or PlatformIO Core.
* ESP32 Board Package: `v2.0.14` or `v3.x` (in Boards Manager).

### Flashing via Arduino IDE
1. Open [`firmware_wifi_softap/firmware_wifi_softap.ino`](firmware_wifi_softap/firmware_wifi_softap.ino).
2. Under **Tools $\to$ Board**, choose **ESP32 Dev Module**.
3. Set **Upload Speed** to **921600** (or 115200) and **CPU Frequency** to **240MHz**.
4. (Optional) In lines 27–28, set your preferred Wi-Fi Hotspot SSID and password:
   ```cpp
   const char* HOTSPOT_SSID = "FBI-VAN";
   const char* HOTSPOT_PASS = "87654321";
   ```
5. Click **Upload (➡️)**.
6. Open **Tools $\to$ Serial Monitor** at **115200 baud** to verify boot logs and connection status.

---

## 📱 Connecting with the Android Music App

1. Turn on your phone's **Personal Hotspot** (configured with your SSID & Password, 2.4 GHz band).
2. Power on the ESP32. It will connect within 3–5 seconds (or start a fallback AP `ESP32-HiFi-Hotspot` if not found).
3. Open the **Music** app on Android.
4. Go to **Settings $\to$ Audio & Quality $\to$ Output Mode** and select **ESP32 Hi-Fi Direct**.
5. Select your desired DAC mode (**External PCM5102A** or **Internal Built-in DAC**).
6. Start playback—your lossless music streams wirelessly at full bit-perfect fidelity!

---

## 🔍 Troubleshooting & Diagnostics

| Symptom | Probable Cause | Remedy |
| :--- | :--- | :--- |
| **Total silence on PCM5102A** | SCK pin floating | Tie `SCK` directly to `GND`. Check that `XSMT` is tied to 3.3V (unmuted). |
| **White noise / Static sound** | Frame alignment loss | Ensure firmware has 8-byte frame reset (`& ~7u`). Restart stream. |
| **Digital squeal into amp** | Pin conflict | Use `GPIO 27` (BCK) and `GPIO 14` (WS) for PCM5102A; keep `GPIO 25/26` exclusively for Internal DAC. |
| **Audio dropouts / crackle** | Wi-Fi modem sleep | Ensure `WiFi.setSleep(false)` is active in firmware. Keep phone within 5 meters. |
| **Loud pop on start/stop** | Internal DAC DC jump | Firmware uses `outputDmaSilence()` with mid-scale `0x8000` (1.65V) to eliminate pops. |
| **Hotspot not detected** | 5 GHz band mismatch | ESP32-WROOM-32 supports **2.4 GHz only**. Ensure hotspot is set to 2.4 GHz. |

---

## 🙏 Credits & Acknowledgements

The Android client in this repository is built directly upon **Stash**, originally created, designed, and architected by **Rawnald Clark** ([@rawnaldclark](https://github.com/rawnaldclark) · `rawnaldclark@gmail.com`).

With over **2,600+ commits** of extraordinary engineering, Rawnald Clark authored the entire core of Stash:
- The multi-module Android Clean Architecture, Jetpack Compose UI, and Room offline database.
- The Media3/ExoPlayer audio service, signal path diagnostics, and gapless playback.
- The lossless FLAC / Tidal / Qobuz streaming relays and metadata embedding engines.
- The Spotify & YouTube Music sync integrations.

Immense gratitude and credit to **Rawnald Clark** for his immense contribution to open-source music streaming.

---

## 📜 License

```text
Copyright (C) 2026 Gokul E. & Contributors
Base architecture upstream (C) Rawnald Clark.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.
```
