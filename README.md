# HIFI Music System & ESP32 Wireless Audio DAC
## Technical Reference Manual & System Documentation

```
Document Version : 2.4.0-REV2
Target Hardware  : ESP32-D0WDQ6 / ESP32-WROOM-32 + TI PCM5102A (I2S)
Target Client    : Android 8.0+ (API 26–35)
Transport Layer  : IEEE 802.11 b/g/n (TCP Socket @ Port 8888)
Audio Encoding   : 24-bit Linear PCM / 96.0 kHz / Stereo (in 32-bit I2S subframes)
Effective Bitrate: 3,072 kbps (Continuous Audio Stream)
License          : GNU General Public License v3.0 (GPL-3.0)
```

---

### Table of Contents

1. [System Architecture](#1-system-architecture)
2. [Hardware Specification & Pinouts](#2-hardware-specification--pinouts)
3. [Network & Audio Transport Protocol](#3-network--audio-transport-protocol)
4. [Android Audio Pipeline & DSP](#4-android-audio-pipeline--dsp)
5. [Autoplay & Recommendation Subsystem](#5-autoplay--recommendation-subsystem)
6. [Lossless Online FLAC Relay Engine](#6-lossless-online-flac-relay-engine)
7. [Firmware Setup & Flashing Guide](#7-firmware-setup--flashing-guide)
8. [Client Installation & Configuration](#8-client-installation--configuration)
9. [Developer Build & Toolchain](#9-developer-build--toolchain)
10. [Troubleshooting & Diagnostics](#10-troubleshooting--diagnostics)

---

## 1. System Architecture

The system delivers bit-perfect, ultra-low-latency 24-bit/96 kHz audio transmission from an Android client to an external physical DAC over an ad-hoc or local Wi-Fi link, bypassing the lossy Android system mixer (`AudioFlinger`) and bandwidth-limited Bluetooth codecs (SBC/AAC).

```
+---------------------------------------------------------------------------------------+
|                                    ANDROID CLIENT                                     |
|                                                                                       |
|  [ Local FLAC / Online Tidal Relay ] ---> [ Media3 / ExoPlayer Bitstream Decoder ]    |
|                                                              |                        |
|                                           [ Sample Converter: 24-bit in 32-bit LE ]   |
|                                                              |                        |
|                     +----------------------------------------+                        |
|                     |                                        |                        |
|          [ Output: Internal AudioTrack ]          [ Output: ESP32 Hi-Fi Direct ]      |
|          (Standard Wired / BT Path)               (Esp32SocketStreamer TCP Client)    |
|                                                              | (Port 8888, Non-block) |
+--------------------------------------------------------------|------------------------+
                                                               |
                                            IEEE 802.11n Wi-Fi | TCP Socket Link
                                            (~3.072 Mbps raw)  | 192.168.43.x:8888
                                                               v
+---------------------------------------------------------------------------------------+
|                                 ESP32 RECEIVER & DAC                                  |
|                                                                                       |
|  [ FreeRTOS TCP Listener ] ---> [ Circular Ring Buffer (32 KB DMA Ring) ]             |
|                                                |                                      |
|                                     [ ESP32 Audio APLL Clock Generator ]             |
|                                                | (MCLK / BCLK / WS Sync)              |
|                                                v                                      |
|                                  [ Hardware I2S0 Peripheral ]                         |
|                                  (96 kHz, 32-bit slot, MSB First)                     |
|                                                |                                      |
|                                                v (BCK, LRCK, DIN)                     |
|                                  [ Texas Instruments PCM5102A DAC ]                   |
|                                                |                                      |
|                                                v                                      |
|                               [ Analog Line Out (L+, GND, R+) ]                       |
|                                                |                                      |
|                                                v                                      |
|                                [ Stereo Audio Amplifier / Monitors ]                  |
+---------------------------------------------------------------------------------------+
```

---

## 2. Hardware Specification & Pinouts

### 2.1 Microcontroller & DAC Interface

The receiver firmware is designed for Espressif **ESP32-WROOM-32** (or NodeMCU-32S / ESP32 DevKitC) interfacing with a **Texas Instruments PCM5102A** 32-bit / 384 kHz stereo audio DAC over the inter-IC sound (I2S) bus.

| Signal Name | ESP32 GPIO | PCM5102A Pin | Wire Color (Typical) | Electrical Specification | Description |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **VCC** | **VIN (5V)** / **3V3** | `VCC` | Red | $3.3\,\text{V} - 5.0\,\text{V}$ DC | Logic and analog rail power supply |
| **GND** | **GND** | `GND` | Black | $0\,\text{V}$ System Ground | Common ground plane |
| **BCLK** | **GPIO 26** | `BCK` | Yellow | $6.144\,\text{MHz}$ Square Wave | Continuous bit clock ($96\,\text{kHz} \times 32 \times 2$) |
| **LRCK / WS** | **GPIO 25** | `LCK` | Green | $96.0\,\text{kHz}$ Square Wave | Word select ($0 = \text{Left}, 1 = \text{Right}$) |
| **DATA / DOUT** | **GPIO 22** | `DIN` | Blue | CMOS Level Digital Audio | Serial PCM audio data stream |
| **SCK / MCLK** | **GND** | `SCK` | Black (to GND) | Pulled to $0\,\text{V}$ Logic Low | Directs PCM5102A to generate internal PLL |

### 2.2 PCM5102A Hardware Jumper Configuration

Inspect the reverse side of the PCM5102A module. The solder bridges configure the onboard hardware registers:

```
  [1] FLT  -> GND (Low latency digital filter / Normal latency mode)
  [2] DEMP -> GND (De-emphasis digital filter disabled)
  [3] XSMT -> VCC (Soft un-mute active; pulled high through 10k resistor)
  [4] FMT  -> GND (Standard Philips I2S format, left-justified clocking)
```

### 2.3 Analog Output Stage & 3-Wire Interconnect

To prevent $(L - R)$ differential cancellation or mono sum collapse at the power amplifier, the output must be terminated in a 3-wire unbalanced line-level stereo configuration:

```
           +-----------------------------------------------+
           |    PCM5102A 3.5mm Jack / Solder Terminals     |
           +-----------------------------------------------+
                     |               |               |
                     | Pin L         | Pin G (GND)   | Pin R
                     v               v               v
                [ Left Signal ]  [ System GND ] [ Right Signal ]
                     |               |               |
                     v               v               v
              [ Left Pre-In ]  [ Chassis GND ]  [ Right Pre-In ]
           +-----------------------------------------------+
           |       Analog Stereo Amplifier Input Board     |
           +-----------------------------------------------+
```

---

## 3. Network & Audio Transport Protocol

### 3.1 Network Topology

```
                  +-----------------------------------+
                  |   Android Client Hotspot / Router |
                  |   SSID : Stash-Hotspot            |
                  |   Pass : 12345678                 |
                  |   Subnet: 192.168.43.0/24         |
                  +-----------------------------------+
                                    |
            +-----------------------+-----------------------+
            |                                               |
            v (DHCP Lease: 192.168.43.1)                    v (DHCP Lease: 192.168.43.x)
   [ Android Controller App ]                     [ ESP32 Receiver Node ]
   TCP Client Socket (Outbound)                   TCP Server Socket (Listening @ 8888)
```

- **Transport:** Raw Streaming TCP Sockets over IPv4 (`AF_INET`, `SOCK_STREAM`).
- **Target Port:** `8888`.
- **Packet Structure:** Frameless, continuous binary byte stream. No RTP/RTSP container overhead.

### 3.2 PCM Frame Serialization Format

Audio frames are encoded as uncompressed, linear little-endian 24-bit samples packed into 32-bit DMA boundaries:

```
 Byte 0       Byte 1       Byte 2       Byte 3        Byte 4       Byte 5       Byte 6       Byte 7
+------------+------------+------------+------------++------------+------------+------------+------------+
| Left LSB   | Left Mid   | Left MSB   | Zero Pad   || Right LSB  | Right Mid  | Right MSB  | Zero Pad   |
| (Bits 0-7) | (Bits 8-15)|(Bits 16-23)| (0x00)     || (Bits 0-7) | (Bits 8-15)|(Bits 16-23)| (0x00)     |
+------------+------------+------------+------------++------------+------------+------------+------------+
|<----------------- LEFT CHANNEL SAMPLE ------------>||<---------------- RIGHT CHANNEL SAMPLE ----------->|
|<--------------------------------------- 1 STEREO FRAME (8 BYTES) ------------------------------------->|
```

### 3.3 Throughput & Bandwidth Computation

$$\text{Sample Rate} = 96{,}000\,\text{Hz}$$
$$\text{Bit Depth per Slot} = 32\,\text{bits}\ (4\,\text{bytes})$$
$$\text{Channels} = 2\ (\text{Stereo})$$
$$\text{Frame Size} = 4\,\text{bytes} \times 2 = 8\,\text{bytes}$$
$$\text{Data Throughput} = 96{,}000 \times 8\,\text{bytes/sec} = 768{,}000\,\text{bytes/sec} \approx 6.144\,\text{Mbps raw bit clock}$$
$$\text{Effective Network Throughput} = 768\,\text{kB/s} \times 8 \approx 3.072\,\text{Mbps}$$

This requirement is comfortably satisfied by standard $20\,\text{MHz}$ channel-width $802.11\text{n}$ Wi-Fi links, which achieve $20\text{--}54\,\text{Mbps}$ indoor sustained throughput.

---

## 4. Android Audio Pipeline & DSP

The Android client (`core/media`) implements a custom `AudioSink` and `ExoPlayer` renderer pipe:

```
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

1. **Native Decoding:** Uses high-efficiency C++ FLAC decoders via Android Media3, preserving $24\text{-bit}/96\,\text{kHz}$ resolution without resampling.
2. **Clock Decoupling:** When routing to the ESP32 socket streamer, the local `AudioTrack` receives digital silence to anchor timeline synchronization while the raw binary stream pumps asynchronously through non-blocking TCP sockets.
3. **Buffer Management:** Employs a low-watermark flow controller to prevent underruns or buffer bloat.

---

## 5. Autoplay & Recommendation Subsystem

To replicate seamless playback (as in Spotify Radio), the app includes a dual-tier recommendation engine that automatically prevents queue exhaustion.

```
                                [ Active Track Nearing End ]
                                             |
                                             v
                           { Are downloaded / local tracks available? }
                                    /                 \
                                   / (Yes)             \ (No)
                                  v                     v
                [ Local Recommendation Engine ]     [ Remote YouTube InnerTube Engine ]
                - Scans Library tracks (KNN)        - Resolves Canonical VideoId
                - Matches Tempo, Artist, Genre      - InnerTube /next API Query
                - Zero network dependency           - Related Carousel Extraction
                                  \                     /
                                   \                   /
                                    v                 v
                         [ Append 10-12 Candidates to Active Media3 Queue ]
                                             |
                                             v
                       [ Playback transitions seamlessly without pause ]
```

### 5.1 Fallback Hierarchy
1. **Local Feature Matcher:** Matches artists and genres across on-device storage.
2. **YouTube Music InnerTube Fallback:** Bypasses Last.fm API dependencies. Queries the `/next` endpoint and `RelatedResponseParser` directly, retrieving 12–30 curated recommendations.
3. **Queue Widening (`widen()`):** As the queue drains, the station engine queries related artist catalogues, guaranteeing indefinite continuous streaming.

---

## 6. Lossless Online FLAC Relay Engine

The streaming layer connects to authenticated lossless relays:

- **Authentication:** Elliptic Curve Digital Signature Algorithm (ECDSA) with the NIST P-256 (`secp256r1`) curve.
- **Embedded Public Key:** Verified against the relay distribution network to establish cryptographically validated handshakes.
- **Dynamic Stream Fallback:**
  - `Tier 1`: Studio Master FLAC ($24\text{-bit}/96\,\text{kHz}$ or $24\text{-bit}/48\,\text{kHz}$).
  - `Tier 2`: Redbook CD FLAC ($16\text{-bit}/44.1\,\text{kHz}$).
  - `Tier 3`: High-Bitrate AAC / OPUS fallback (low-bandwidth mode).

---

## 7. Firmware Setup & Flashing Guide

### 7.1 Toolchain Requirements
- **Arduino IDE** (v2.x) or **PlatformIO Core / CLI**.
- **Espressif ESP32 Board Core:** Version 2.0.14 or 3.x.

### 7.2 Flashing via PlatformIO CLI
```bash
# Navigate to the firmware workspace
cd firmware_wifi_softap

# Build and flash using PlatformIO
pio run --target upload --upload-port COM3

# Monitor serial output (115200 baud)
pio device monitor -b 115200
```

### 7.3 Flashing via Arduino IDE
1. Open [`firmware_wifi_softap/firmware_wifi_softap.ino`](firmware_wifi_softap/firmware_wifi_softap.ino).
2. Under **Tools $\to$ Board**, select **ESP32 Dev Module**.
3. Configure the following parameters:
   - **CPU Frequency:** $240\,\text{MHz}$ (High Performance).
   - **Flash Frequency:** $80\,\text{MHz}$.
   - **Partition Scheme:** Minimal SPIFFS (1.9MB APP with OTA / Large SPIFFS).
   - **Core Debug Level:** None (or Info for debugging).
4. Connect the ESP32 over USB and click **Upload**.

---

## 8. Client Installation & Configuration

### 8.1 Android APK Installation
1. Download the latest compiled package: [**Music.apk**](https://github.com/Gokul3611/HIFI_music_app/releases/download/latest/Music.apk).
2. Transfer to device or open directly in Android Chrome.
3. Tap `Music.apk` $\to$ **Settings** $\to$ **Allow from this source** (if sideloading).
4. Launch the **Music** application.

### 8.2 Hardware Output Pairing
1. Enable **Personal Hotspot** on Android:
   - **SSID / Network Name:** `Stash-Hotspot`
   - **Security:** WPA2-PSK
   - **Password:** `12345678`
   - **Band:** $2.4\,\text{GHz}$ or $5\,\text{GHz}$
2. Power the ESP32. The onboard LED will blink while negotiating DHCP, then stay solid once connected.
3. In **Music**, go to **Settings $\to$ Audio & Quality $\to$ Output Selection**.
4. Select **ESP32 Hi-Fi Direct**. Audio routing shifts from the handset speakers to the analog DAC outputs immediately.

---

## 9. Developer Build & Toolchain

### 9.1 Environment Prerequisites
- **JDK:** OpenJDK 17 (Microsoft or Eclipse Temurin).
- **Android SDK:** Platform API Level 35.
- **NDK:** Version `27.0.12077973`.
- **Build System:** Gradle 8.12 with Android Gradle Plugin (AGP) 8.8.x.

### 9.2 Build Commands

```bash
# Clone the repository
git clone https://github.com/Gokul3611/HIFI_music_app.git
cd HIFI_music_app

# Run compile-time checks and linting
./gradlew lintDebug

# Execute unit test suite (core:media, core:data, data:download)
./gradlew testDebugUnitTest

# Assemble production signed Release APK
./gradlew assembleRelease

# Direct install to ADB-connected target
./gradlew installDebug
```

---

## 10. Troubleshooting & Diagnostics

### 10.1 Diagnostics Matrix

| Symptom | Probable Cause | Diagnostic Command / Remedy |
| :--- | :--- | :--- |
| **No audio output from DAC** | SCK pin floating | Tie PCM5102A `SCK` pin directly to `GND` to engage internal PLL. |
| **Distorted / Static Noise** | Bit clock / slot mismatch | Ensure firmware specifies `I2S_BITS_PER_SAMPLE_32BIT` and PCM is LE. |
| **Audio Stutter / Dropouts** | Wi-Fi TCP buffer exhaustion | Lower phone-to-ESP32 distance; ensure Hotspot is set to $2.4\,\text{GHz}$ channel 6 or 11. |
| **Mono sound output** | Audio Ground floating | Connect all 3 wires (L+, GND, R+) from PCM5102A to the amplifier input. |
| **Autoplay does not trigger** | Autoplay Radio toggled off | Navigate to **Settings $\to$ Playback $\to$ Autoplay Radio** and toggle ON. |
| **In-app update failure** | Network sandbox or repository mismatch | Ensure client tracks `Gokul3611/HIFI_music_app` repository release assets. |

### 10.2 Serial Log Monitoring (`logcat`)
```bash
# Monitor Android audio pipeline output
adb logcat -s StashPlayback PlayerRepositoryImpl Esp32SocketStreamer

# Expected streaming handshake:
# I/Esp32SocketStreamer: Connected to ESP32 DAC @ 192.168.43.155:8888 (TCP)
# I/PlayerRepositoryImpl: Routing active audio -> 24-bit / 96000 Hz stereo PCM
```

---

## 📜 License

```
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
