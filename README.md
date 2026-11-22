# HIFI Music System & ESP32 Wireless Audio DAC
## Technical Reference Manual & System Documentation

```text
Document Version : 2.5.0-REV4
Target Hardware  : ESP32-D0WDQ6 / ESP32-WROOM-32 + TI PCM5102A (I2S) / Internal DAC
Target Client    : Android 8.0+ (API 26–35)
Transport Layer  : IEEE 802.11 b/g/n (TCP Socket @ Port 8888, UDP Control @ Port 8889)
Audio Encoding   : Signed 32-bit LE Stereo PCM (S32_LE, 8 bytes per frame)
Supported Rates  : 8.0 kHz – 192.0 kHz (Bit-Perfect Linear PCM)
Effective Bitrate: Up to 12.3 Mbps (Continuous Uncompressed Payload)
License          : GNU General Public License v3.0 (GPL-3.0)
```

---

### Table of Contents

1. [System Architecture](#1-system-architecture)
2. [Hardware Specification & Pinouts](#2-hardware-specification--pinouts)
3. [Network & Audio Transport Protocol](#3-network--audio-transport-protocol)
4. [Firmware Audio Engine & Concurrency](#4-firmware-audio-engine--concurrency)
5. [Android Audio Pipeline & DSP](#5-android-audio-pipeline--dsp)
6. [Autoplay & Recommendation Subsystem](#6-autoplay--recommendation-subsystem)
7. [Lossless Online FLAC Relay Engine](#7-lossless-online-flac-relay-engine)
8. [Firmware Setup & Flashing Guide](#8-firmware-setup--flashing-guide)
9. [Client Installation & Configuration](#9-client-installation--configuration)
10. [Developer Build & Toolchain](#10-developer-build--toolchain)
11. [Troubleshooting & Diagnostics](#11-troubleshooting--diagnostics)
12. [Credits & License](#12-credits--license)

---

## 1. System Architecture

The system delivers bit-perfect, ultra-low-latency high-resolution audio transmission from an Android client to an external physical DAC over an ad-hoc or local Wi-Fi link, bypassing the lossy Android system mixer (`AudioFlinger`) and bandwidth-limited Bluetooth codecs (SBC/AAC).

```text
+---------------------------------------------------------------------------------------+
|                                    ANDROID CLIENT                                     |
|                                                                                       |
|  [ Local FLAC / Online Tidal Relay ] ---> [ Media3 / ExoPlayer Bitstream Decoder ]    |
|                                                              |                        |
|                                           [ Sample Converter: Signed 32-bit LE ]      |
|                                                              |                        |
|                     +----------------------------------------+                        |
|                     |                                        |                        |
|          [ Output: Internal AudioTrack ]          [ Output: ESP32 Hi-Fi Direct ]      |
|          (Standard Wired / BT Path)               (Esp32SocketStreamer TCP Client)    |
|                                                              | (Port 8888, Non-block) |
+--------------------------------------------------------------|------------------------+
                                                               |
                                            IEEE 802.11n Wi-Fi | TCP Stream
                                            (Up to 12.3 Mbps)  | 192.168.43.x:8888
                                                               v
+---------------------------------------------------------------------------------------+
|                                 ESP32 RECEIVER & DAC                                  |
|                                                                                       |
|  [ FreeRTOS TCP Listener (Pri 1) ] ---> [ SPSC Circular Ring Buffer (64 KB SRAM) ]   |
|                                                              |                        |
|                     +----------------------------------------+                        |
|                     | Handshake (Pause/Resume Semaphores)                             |
|                     v                                                                 |
|  [ Real-Time Audio Worker Task (Pri 12, Core 1) ]                                     |
|                     |                                                                 |
|         +-----------+-----------+                                                     |
|         |                       |                                                     |
|         v (Mode 1: External)    v (Mode 2: Internal Built-in DAC)                     |
|  [ Hardware I2S0 Peripheral ]  [ Dual 8-bit DAC with 1-LSB TPDF Dither & 1.65V Bias] |
|  (GPIO 27, 14, 22 - APLL)      (GPIO 25: DAC1 Left, GPIO 26: DAC2 Right)              |
|         |                       |                                                     |
|         v                       v                                                     |
|  [ TI PCM5102A DAC ]     [ Direct Line-Out to Amp ]                                   |
|         |                       |                                                     |
|         +-----------+-----------+                                                     |
|                     |                                                                 |
|                     v                                                                 |
|         [ Analog Stereo Amplifier / Studio Monitors ]                                 |
+---------------------------------------------------------------------------------------+
```

---

## 2. Hardware Specification & Pinouts

### 2.1 Mode 1: External TI PCM5102A 32-bit DAC Interface

The receiver firmware routes the external I2S bus to dedicated GPIOs, keeping high-frequency digital clocks isolated from the analog DAC pins (GPIO 25/26):

| Signal Name | ESP32 GPIO | PCM5102A Pin | Wire Function | Electrical Specification | Description |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **VCC** | **VIN (5V)** / **3V3** | `VCC` | Power Rail | 3.3V – 5.0V DC | Power supply (5V provides 2.1V RMS headroom) |
| **GND** | **GND** | `GND` | Power Ground | 0V System Ground | Common ground plane |
| **BCLK** | **GPIO 27** | `BCK` | Bit Clock | High-speed square wave | Continuous bit clock (isolated from DAC lines) |
| **LRCK / WS** | **GPIO 14** | `LCK` | Word Select | Frame select wave | Channel framing clock (0 = Left, 1 = Right) |
| **DATA / DOUT** | **GPIO 22** | `DIN` | Serial Data | CMOS level audio | Serial linear PCM audio bitstream |
| **SCK / MCLK** | **GND** | `SCK` | Master Clock | 0V Logic Low | Directs PCM5102A to generate internal PLL |

### 2.2 Mode 2: ESP32 Built-in Dual-Channel 8-bit DAC

| Channel | ESP32 GPIO | Description |
| :--- | :--- | :--- |
| **Left Audio** | **GPIO 25** | DAC Channel 1 (DAC1) with 1-LSB TPDF Dither |
| **Right Audio** | **GPIO 26** | DAC Channel 2 (DAC2) with 1-LSB TPDF Dither |
| **Audio Ground** | **GND** | System Ground Reference |

*Note: The built-in DAC uses mid-scale silence (`0x8000` / 1.65 V DC) on underruns and stops to prevent DC step speaker thumps.*

### 2.3 PCM5102A Hardware Jumper Configuration

Inspect the reverse side of the PCM5102A module. The solder bridges configure the onboard hardware registers:

```text
  [1] FLT  -> GND (Low latency FIR digital filter)
  [2] DEMP -> GND (De-emphasis digital filter disabled)
  [3] XSMT -> VCC (Soft un-mute active; pulled high to 3.3V)
  [4] FMT  -> GND (Standard Philips I2S format)
```

---

## 3. Network & Audio Transport Protocol

### 3.1 Network Topology

```text
                  +-----------------------------------+
                  |   Android Client Hotspot / Router |
                  |   SSID : FBI-VAN (or Stash-Hotspot)|
                  |   Pass : 87654321 / 12345678       |
                  |   Subnet: 192.168.43.0/24         |
                  +-----------------------------------+
                                    |
            +-----------------------+-----------------------+
            |                                               |
            v (DHCP Lease: 192.168.43.1)                    v (DHCP Lease: 192.168.43.x)
   [ Android Controller App ]                     [ ESP32 Receiver Node ]
   TCP Client Socket (Outbound)                   TCP Server Socket (Port 8888)
   UDP Control Socket (Outbound)                  UDP Control Server (Port 8889)
```

### 3.2 PCM Frame Serialization Format

Audio frames are encoded as uncompressed, linear little-endian 32-bit samples (8 bytes per stereo frame):

```text
 Byte 0       Byte 1       Byte 2       Byte 3        Byte 4       Byte 5       Byte 6       Byte 7
+------------+------------+------------+------------++------------+------------+------------+------------+
| Left Byte0 | Left Byte1 | Left Byte2 | Left Byte3 || Right Byte0| Right Byte1| Right Byte2| Right Byte3|
+------------+------------+------------+------------++------------+------------+------------+------------+
|<----------------- LEFT CHANNEL SAMPLE ------------>||<---------------- RIGHT CHANNEL SAMPLE ----------->|
|<--------------------------------------- 1 STEREO FRAME (8 BYTES) ------------------------------------->|
```

### 3.3 Out-of-Band UDP Control (Port 8889)

| Command | Direction | Response | Description |
| :--- | :--- | :--- | :--- |
| `HIFI_DISCOVER` | App $\to$ ESP32 | `HIFI_DAC_HERE MODE:<1\|2>` | Broadcast discovery query. |
| `GET_STATUS` | App $\to$ ESP32 | `HIFI_STATUS:MODE:<m>,RATE:<r>,RSSI:<rssi>,BUFFER:<pct>%` | Telemetry query. |
| `MODE:<1\|2>` | App $\to$ ESP32 | `MODE_ACK:<mode>` / `MODE_NACK:<mode>` | Switches between External PCM5102A (1) and Internal DAC (2). |
| `RATE:<sample_rate>` | App $\to$ ESP32 | `RATE_ACK:<rate>` / `RATE_NACK:<rate>` | Switches sample rate (e.g. 44100, 48000, 96000, 192000). |

---

## 4. Firmware Audio Engine & Concurrency

1. **Starvation-Free Handshake:** Eliminates FreeRTOS priority inversion and mutex starvation between `audioWorkerTask` (Priority 12, Core 1) and `loop()` (Priority 1, Core 1) using binary semaphores (`s_worker_paused`, `s_worker_resume`).
2. **Lockless SPSC 64 KB Circular Ring Buffer:** Uses 32-bit internal SRAM ($2^{16}$ power-of-2) with atomic acquire/release semantics and optimized 2-segment `memcpy` operations.
3. **Frame-Aligned Resets:** Restores 8-byte frame boundaries (`& ~7u`) on reconnects or mode switches, preventing L/R channel swaps or bit-shifted static.
4. **Adaptive Anti-Jitter Prebuffering:** Watermark scales dynamically with sample rate ($\approx 40\text{ ms}$ cushion) to prevent buffer starvation.
5. **Dynamic I2S Write Timeouts:** Prevents false timeouts at low sample rates (8 kHz) while keeping real-time responsiveness up to 192 kHz.
6. **Pop-Free DC Silence:** Output silence helper writes `0x8000` (1.65 V DC mid-rail) for internal DAC underruns and stops, preventing violent 0 V step-transient speaker pops.

---

## 5. Android Audio Pipeline & DSP

The Android client (`core/media`) implements a custom `AudioSink` and `ExoPlayer` renderer pipe:

```text
[ Input Bitstream (FLAC/ALAC) ]
             |
             v
[ Media3 DefaultAudioSink ]
             |
             v
[ AudioTrack / C++ NDK Pipeline ]
             |
             +---> [ Esp32AudioSink ] ---> [ TCP Socket Buffer (64 KB) ] ---> Wi-Fi Net
             |
             +---> [ Android AudioTrack ] (Muted zeroes when ESP32 output active)
```

1. **Native Decoding:** Uses high-efficiency C++ FLAC decoders via Android Media3, preserving bit-perfect linear PCM resolution without resampling.
2. **Clock Decoupling:** When routing to the ESP32 socket streamer, the local `AudioTrack` receives digital silence to anchor timeline synchronization while the raw binary stream pumps asynchronously through non-blocking TCP sockets.
3. **Buffer Management:** Employs a low-watermark flow controller to prevent underruns or buffer bloat.

---

## 6. Autoplay & Recommendation Subsystem

To replicate seamless playback (as in Spotify Radio), the app includes a dual-tier recommendation engine that automatically prevents queue exhaustion:

1. **Local Feature Matcher:** Matches artists and genres across on-device storage.
2. **YouTube Music InnerTube Fallback:** Queries the `/next` endpoint directly, retrieving curated recommendations.
3. **Queue Widening (`widen()`):** As the queue drains, the station engine queries related artist catalogues, guaranteeing continuous playback.

---

## 7. Lossless Online FLAC Relay Engine

The streaming layer connects to authenticated lossless relays:
- **Authentication:** Elliptic Curve Digital Signature Algorithm (ECDSA) with the NIST P-256 (`secp256r1`) curve.
- **Embedded Public Key:** Verified against the relay distribution network to establish cryptographically validated handshakes.
- **Dynamic Stream Fallback:**
  - `Tier 1`: Studio Master FLAC (24-bit / 96 kHz or 24-bit / 48 kHz).
  - `Tier 2`: Redbook CD FLAC (16-bit / 44.1 kHz).
  - `Tier 3`: High-Bitrate AAC / OPUS fallback (low-bandwidth mode).

---

## 8. Firmware Setup & Flashing Guide

### 8.1 Toolchain Requirements
- **Arduino IDE** (v2.x) or **PlatformIO Core / CLI**.
- **Espressif ESP32 Board Core:** Version 2.0.14 or 3.x.

### 8.2 Flashing via Arduino IDE
1. Open [`firmware_wifi_softap/firmware_wifi_softap.ino`](firmware_wifi_softap/firmware_wifi_softap.ino).
2. Under **Tools → Board**, select **ESP32 Dev Module**.
3. Configure the parameters:
   - **CPU Frequency:** 240 MHz.
   - **Upload Speed:** 921600 (or 115200).
4. Connect the ESP32 over USB and click **Upload**.

---

## 9. Client Installation & Configuration

### 9.1 Android APK Installation
1. Download the latest compiled package: [**Music.apk**](https://github.com/Gokul3611/HIFI_music_app/releases/download/latest/Music.apk).
2. Install the APK on your device.
3. Launch the **Music** application.

### 9.2 Hardware Output Pairing
1. Enable **Personal Hotspot** on Android (2.4 GHz Band, matching credentials in sketch).
2. Power the ESP32. It connects in ~3 seconds.
3. In **Music**, navigate to **Settings → Audio & Quality → Output Mode**.
4. Select **ESP32 Hi-Fi Direct** and choose your preferred DAC mode.

---

## 10. Developer Build & Toolchain

```bash
# Clone the repository
git clone https://github.com/Gokul3611/HIFI_music_app.git
cd HIFI_music_app

# Execute unit test suite
./gradlew testDebugUnitTest

# Assemble production signed Release APK
./gradlew assembleRelease
```

---

## 11. Troubleshooting & Diagnostics

| Symptom | Probable Cause | Diagnostic Command / Remedy |
| :--- | :--- | :--- |
| **No audio output from DAC** | SCK pin floating | Tie PCM5102A `SCK` pin directly to `GND` to engage internal PLL. |
| **Distorted / Static Noise** | Frame alignment loss | Ensure firmware has 8-byte frame reset (`& ~7u`). Restart stream. |
| **Digital squeal into amp** | Pin conflict | Use `GPIO 27` (BCK) and `GPIO 14` (WS) for PCM5102A; keep `GPIO 25/26` exclusively for Internal DAC. |
| **Audio Stutter / Dropouts** | Wi-Fi modem sleep | Firmware automatically sets `WiFi.setSleep(false)` on connection. |
| **Loud pop on start/stop** | Internal DAC DC jump | Firmware uses `outputDmaSilence()` with mid-scale `0x8000` (1.65V) to eliminate pops. |

---

## 12. Credits & License

### 🙏 Credits & Acknowledgements
This application is built upon the phenomenal foundation of **Stash**, conceived, designed, and architected by **Rawnald Clark** ([@rawnaldclark](https://github.com/rawnaldclark)).

Over **2,600+ commits** of tireless open-source craftsmanship by Rawnald Clark made this platform possible:
- **Core Architecture:** The reactive multi-module Android Clean Architecture (`:core`, `:data`, `:feature`), Room FTS offline database, and WorkManager sync engine.
- **Playback & Media3 Engine:** The robust ExoPlayer streaming service, background audio focus handling, lockscreen controllers, and signal path diagnostics.
- **Lossless & Streaming Pipelines:** Cryptographic Tidal/Qobuz relay integration, multi-tier audio resolvers, and adaptive format negotiation.
- **Metadata & Tagging:** In-process FFmpeg Vorbis/Opus comment embedder, cover art processing, and library file organization.
- **InnerTube & Multi-Platform Sync:** Innovative client token scraping, Liked Songs synchronization, and cross-platform music imports.

### 📜 License
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
