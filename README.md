# 🎵 Music Hi-Fi — Audiophile Android Player & ESP32 Wireless Hi-Res DAC

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-ESP32%20WROOM--32-red.svg)](https://www.espressif.com/)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026--35)-green.svg)](https://developer.android.com/)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a%20%7C%20armeabi--v7a-orange.svg)](#)
[![Audio](https://img.shields.io/badge/Audio-32--bit%20%7C%20192%20kHz%20PCM-gold.svg)](#)

An open-source, end-to-end hi-res music system pairing a **multi-module Android music player** (built on **Stash**) with a **custom ESP32 wireless audio receiver**. It streams uncompressed 32-bit linear PCM (`S32_LE`) over a local Wi-Fi TCP socket—bypassing Android's lossy `AudioFlinger` mixer, OS resampling, and Bluetooth compression—while keeping your phone's speaker free for YouTube, notifications, and calls.

---

## 📋 Privacy & User Data Notice

> [!NOTE]
> **User Name & Telemetry Collection:** To maintain our user registry and track active installs, **Music Hi-Fi** collects the **display name**, **username / account ID**, and **connected service name** (Spotify, YouTube Music, or Discord) when you sign in to a third-party account. If no account is connected, a basic **device manufacturer and model** fallback is recorded on first launch.
>
> **No passwords, OAuth tokens, or private cookies are ever transmitted or collected.** All service credentials remain encrypted on your device. See [`PRIVACY.md`](PRIVACY.md) for full network and privacy details.

---

## ✨ Complete Feature List

### 📱 Android Client Features (`:app`, `:core:*`, `:data:*`, `:feature:*`)

#### 🎧 Audiophile Playback, DSP & Output Routing
- **Four Hi-Res Output Modes:**
  - **Standard:** Android shared mixer playback.
  - **Match Rate:** Android 14+ USB DAC native sample-rate matching.
  - **Bit-Perfect:** Android 14+ USB DAC direct hardware path bypassing DSP and OS mixer.
  - **ESP32 Hi-Fi Direct:** Uncompressed 32-bit `S32_LE` stereo PCM streaming over 2.4 GHz Wi-Fi TCP (`Port 8888`) with UDP control (`Port 8889`).
- **Stream Isolation & Independent Volume:** Disables Android audio focus loss in `ESP32 Hi-Fi Direct` mode so calls, notifications, and videos play on your phone speaker without interrupting or ducking your ESP32 stream, complete with an independent perceptual software volume slider.
- **Dual-Player Gapless & Crossfade Engine:** Seamless track transitions powered by Media3 / ExoPlayer (`CrossfadeEngine.kt`).
- **Custom DSP & Native Pitch Engine:** Multi-band Equalizer, loudness normalization, and **432 Hz / custom pitch & tempo tuning** powered by the in-tree C++ `stash_tuning` Signalsmith JNI library.
- **Signal-Path Diagnostics:** Real-time inspection sheet displaying source codec, sample rate, bit depth, active DSP stages, and output hardware route.

#### 🧠 AI Smart Shuffle & Vibe Continuity
- **AI Smart Shuffle (`AutoAwesome` ✨):** Dedicated AI shuffle button placed cleanly beside classic Shuffle across Library, Liked Songs, and Device tabs.
- **Genre & Vibe Continuity Engine (`LocalRecommendationEngine`):**
  - Classifies tracks into stylistic clusters (Romantic/Acoustic/Ballad, Rock/Metal, Folk/Country, Hip-Hop/R&B, Electronic/Dance, Classical/Ambient/Lo-Fi, Pop).
  - Enforces **strict cross-genre barriers** so romantic/mellow sessions never abruptly jump to heavy rock, metal, or loud folk.
  - $O(N)$ profile precomputation with artist/collaborator tokenization (`feat.`, `ft.`, `&`, `x`), album continuity, duration/tempo proximity, and hi-res format synergy—running off the main thread for instant, crash-free queuing even on 10,000+ track libraries.
- **Intelligent Autoplay:** Automatically extends finished queues with vibe-matched recommendations from your library.

#### 🌐 Multi-Source Music, Lossless & Cloud Storage
- **Local Audio Library:** Fast device scanner & importer supporting FLAC, WAV, ALAC, Opus, AAC, and MP3 with full metadata/artwork extraction.
- **Streaming Integrations:**
  - **Spotify:** Library sync, playlists, and liked songs mirroring.
  - **YouTube & YouTube Music:** Search, library sync, artist/album browsing, and `yt-dlp` playback.
  - **SoundCloud:** Stream playback and account integration.
  - **Lossless Relay & Fallbacks:** Qobuz account streaming, Tidal relay / custom Tidal server support, automatic FLAC upgrade queue for lossy downloads, and JioSaavn AAC 320 / YouTube Opus fallback.
- **Cloud Storage Integration:** Browse, stream, and back up music directly from **Google Drive**, **Dropbox**, and **OneDrive**.

#### 🎨 UI, Lyrics, Widgets & Ecosystem
- **Minimalistic Jetpack Glance Home Screen Widgets:**
  - **Wide (`4×1`)** and **Square (`2×2`)** resizable widgets with squircle `RGB_565` low-memory album cover art, dynamic track-accent Play/Pause button, Previous/Next controls, and zero-bitmap error fallback.
  - **Headless Background Control:** Tapping widget playback controls communicates directly with `StashPlaybackService` without launching the app activity.
- **Word- & Line-Synced Lyrics:** Multi-provider cascade (Apple Music / paxsenix, Spicy Lyrics, LRCLIB, KuGou, lrc.red, Binimum, YouTube Music).
- **Stats, Scrobbling & Social:** Listening history & monthly Recap, Last.fm & ListenBrainz scrobbling, Discord Rich Presence, Google Cast, Listen Together synchronized rooms, Shared Mixes, Community feed, and Web Link device sync.
- **Universal ARM Builds & In-App Updater:** Supports both `arm64-v8a` (64-bit) and `armeabi-v7a` (32-bit) devices under 80 MB with automatic GitHub Releases update checking.

---

### 🔌 ESP32 Wireless Receiver Features ([`firmware_wifi_softap/firmware_wifi_softap.ino`](firmware_wifi_softap/firmware_wifi_softap.ino))

- **Dual-Mode DAC Switching (Live from App):**
  - **Mode 1 — External TI PCM5102A 32-Bit I2S DAC:** Studio master quality (`8 kHz – 192 kHz`) via dedicated `I2S_NUM_0` APLL pins (`GPIO 27 / 14 / 22`), isolated from analog pins to eliminate digital clock crosstalk.
  - **Mode 2 — ESP32 Built-in 8-Bit DAC:** Zero extra hardware (`GPIO 25 / 26`) with **1-LSB TPDF Dither** and pop-free **0 V ↔ 1.65 V DC bias ramping**.
- **Real-Time FreeRTOS Audio Engine:**
  - **Lock-Free 64 KB SPSC Ring Buffer:** Statically allocated in internal 32-bit SRAM (`__attribute__((aligned(4)))`, zero heap fragmentation) using acquire/release atomic pointers.
  - **Starvation-Free Concurrency:** Binary semaphore pause/resume handshake between the Core 1 TCP network producer (`Priority 1`) and `audioWorkerTask` DMA consumer (`Priority 12`).
  - **8-Byte Frame Alignment:** Guarantees atomic stereo `[Left: 4B, Right: 4B]` frame boundaries across flushes and reconnects—zero L/R channel swap or static.
  - **Adaptive Anti-Jitter Prebuffering:** Sample-rate-aware prebuffer watermark (~40 ms, clamped `4 KB – 32 KB`) and dynamic I2S DMA timeouts.
  - **Instant `FLUSH` & Rate Switching:** Clears ring buffer cleanly on track skip/seek and dynamically reconfigures APLL sample rates (`8000 – 192000 Hz`).
- **Dual Wi-Fi & Zero-Config Discovery:**
  - Automatically connects to your phone's 2.4 GHz hotspot, or falls back after 10 seconds to a standalone **SoftAP (`ESP32-HiFi-Hotspot` / `12345678`)**.
  - Advertises via **mDNS (`music-dac.local` / `_audio-hifi._tcp`)** and responds to **UDP broadcast discovery (`HIFI_DISCOVER`)** and live telemetry queries (`GET_STATUS` reporting mode, sample rate, Wi-Fi RSSI, and ring-buffer fill %).

---

## 🗂️ Repository Map

| Path | Purpose & Key Entry Points |
| :--- | :--- |
| [`app/`](app/) | Android application shell, Hilt DI graph, Glance widgets (`NowPlayingWidget.kt`), manifest, and assets. |
| [`core/media/`](core/media/) | Playback service (`StashPlaybackService.kt`), crossfade/focus (`CrossfadeEngine.kt`), output routing (`HiResOutputController.kt`, `StashAudioOutput.kt`), AI vibe engine (`LocalRecommendationEngine.kt`), native tuning DSP, and ESP32 streamer (`Esp32SocketStreamer.kt`). |
| [`core/data/`](core/data/) | Repositories, Room database, preferences, and in-app updater (`AppUpdater.kt`). |
| [`core/auth/`](core/auth/) | OAuth/account managers (Spotify, YouTube, SoundCloud, Discord) and user telemetry (`SilentTelemetryReporter.kt`). |
| [`core/ui/`](core/ui/), [`core/model/`](core/model/), [`core/network/`](core/network/), [`core/common/`](core/common/), [`core/addons/`](core/addons/) | Shared Compose components, domain models, HTTP clients, utilities, and addon contracts. |
| [`data/`](data/) | Source & feature data modules: `ytmusic`, `spotify`, `soundcloud`, `cloud`, `download`, `lyrics`. |
| [`feature/`](feature/) | Jetpack Compose UI modules: `home`, `library`, `nowplaying`, `search`, `settings` (`HiResOutputSection.kt`), `sync`, `listening`, `community`, `record`. |
| [`firmware_wifi_softap/`](firmware_wifi_softap/) | ESP32 Arduino/FreeRTOS dual-mode DAC receiver (`firmware_wifi_softap.ino`). |
| [`docs/`](docs/) | Setup guides ([`docs/ESP32_SETUP.md`](docs/ESP32_SETUP.md)) and architecture notes. |
| [`.github/workflows/`](.github/workflows/) | Automated CI APK build & GitHub Release workflow (`build-apk.yml`). |

---

## ⚡ System Architecture

```text
+---------------------------------------------------------------------------------------+
|                                    ANDROID CLIENT                                     |
|                                                                                       |
|  [ FLAC / Hi-Res / Stream ] ---> [ Media3 / ExoPlayer ] ---> [ StashAudioOutput ]     |
|                                                                     |                 |
|     (Phone speaker stays silent for Music;                          v                 |
|      YouTube / notifications / calls play normally)   [ Esp32SocketStreamer ]         |
|                                                       - Perceptual Volume & 8B Align  |
|                                                       - TCP 8888 (S32_LE PCM)         |
|                                                       - UDP 8889 (MODE/RATE/FLUSH)    |
+---------------------------------------------------------------------|-----------------+
                                                                      | 2.4 GHz Wi-Fi
                                                                      v
+---------------------------------------------------------------------------------------+
|                                 ESP32 RECEIVER & DAC                                  |
|                                                                                       |
|  [ loop() TCP Receiver (Core 1, Pri 1) ] ---> [ 64 KB Lock-Free SPSC Ring Buffer ]    |
|                                                            |                          |
|  [ handleControlUdp() (Port 8889) ] <-- Pause/Resume --> [ AudioDMAWorker (Pri 12) ]  |
|                                                            |                          |
|                                            +---------------+---------------+          |
|                                            v                               v          |
|                                 [ Mode 1: I2S0 APLL ]           [ Mode 2: Built-in ]  |
|                                 (GPIO 27, 14, 22)               (GPIO 25, 26)         |
|                                            |                               |          |
|                                            v                               v          |
|                                  [ TI PCM5102A DAC ]            [ Line Out / Amp ]    |
+---------------------------------------------------------------------------------------+
```

---

## 🚀 Quick Start

### 1. Build or Install the Android App
- **Download:** Grab the latest APK from [GitHub Releases](../../releases) or [GitHub Actions](../../actions).
- **Build from source:** Requires **JDK 17**, **Android SDK (`compileSdk 35`)**, and **NDK `27.0.12077973` + CMake `3.22.1`** (for the native C++ tuning/DSP engine):
  ```bash
  ./gradlew assembleDebug        # Build debug APK
  ./gradlew testDebugUnitTest    # Run JVM unit tests
  ```

### 2. Flash the ESP32 Firmware
1. Open [`firmware_wifi_softap/firmware_wifi_softap.ino`](firmware_wifi_softap/firmware_wifi_softap.ino) in [Arduino IDE 2.x](https://www.arduino.cc/en/software) (with ESP32 board package `v2.0.14` or `v3.x`).
2. Select **Tools → Board → ESP32 Dev Module** and **Upload Speed → 921600**.
3. *(Optional)* Update `HOTSPOT_SSID` and `HOTSPOT_PASS` near the top of the sketch to match your phone's 2.4 GHz hotspot (if the hotspot is not found within 10s, the ESP32 starts a standalone AP: `ESP32-HiFi-Hotspot` / `12345678`).
4. Click **Upload**, then open **Serial Monitor** at `115200` baud to see the assigned IP address.

### 3. Wire Your DAC

#### Mode 1: External TI PCM5102A 32-Bit DAC (Recommended)
Up to **32-bit / 192 kHz** studio quality. Keep external I2S pins (`27 / 14 / 22`) separate from internal DAC pins (`25 / 26`).

| ESP32 Pin | PCM5102A Pin | Signal | Notes |
| :--- | :--- | :--- | :--- |
| `VIN` (5V) | `VCC` | Power | 5V recommended for full 2.1V RMS headroom (3.3V also works). |
| `GND` | `GND` | Ground | Common ground. |
| `GPIO 27` | `BCK` | Bit Clock | Dedicated I2S bit clock. |
| `GPIO 14` | `LCK / WS` | Word Select | Left / Right channel clock. |
| `GPIO 22` | `DIN` | Serial Data | Linear PCM data. |
| `GND` | `SCK` | System Clock | **Required:** Tie `SCK` to `GND` to enable the PCM5102A internal PLL. |

*(Board jumpers: `FLT → GND`, `DEMP → GND`, `XSMT → 3.3V`, `FMT → GND`.)*

#### Mode 2: ESP32 Built-in 8-Bit DAC (Zero Extra Hardware)
Drives an amplifier or powered speaker's line input directly with 1-LSB TPDF dither and pop-free 1.65 V bias ramping.

| ESP32 Pin | Amplifier Input | Signal |
| :--- | :--- | :--- |
| `GPIO 25` | Left (`L+`) | Built-in DAC1 |
| `GPIO 26` | Right (`R+`) | Built-in DAC2 |
| `GND` | Ground (`GND`) | Common audio ground |

### 4. Connect & Stream
1. Enable your phone's **2.4 GHz Personal Hotspot** (or connect to `ESP32-HiFi-Hotspot`) and power on the ESP32.
2. Open the **Music** app → **Settings → Audio & Quality → Hi-Res output → ESP32 Hi-Fi Direct**.
3. Tap **Discover** (or **Test Ping** with the ESP32 IP), select **PCM5102A** or **Built-in**, adjust **Stream Volume** as needed, and tap **Connect & Stream**.

---

## 🌐 Wire Protocol Reference

- **TCP Audio Stream (`Port 8888`):** Raw `S32_LE` interleaved stereo PCM (8 bytes/frame). Paced 1:1 by the Android `AudioTrack` silence sink and ESP32 I2S DMA backpressure.
- **mDNS Discovery:** `music-dac.local` (`_audio-hifi._tcp`, Port `8888`).
- **Out-of-Band UDP Control (`Port 8889`):**

| Command | Response | Purpose |
| :--- | :--- | :--- |
| `HIFI_DISCOVER` | `HIFI_DAC_HERE MODE:<1\|2>` | Broadcast discovery. |
| `GET_STATUS` | `HIFI_STATUS:MODE:<m>,RATE:<r>,RSSI:<dbm>,BUFFER:<pct>%` | Live mode, rate, signal, and ring-buffer telemetry. |
| `MODE:<1\|2>` | `MODE_ACK:<mode>` / `MODE_NACK:<mode>` | Live switch between PCM5102A (`1`) and Built-in DAC (`2`). |
| `RATE:<hz>` | `RATE_ACK:<hz>` / `RATE_NACK:<hz>` | Switch sample rate (`8000`–`192000` Hz). |
| `FLUSH` | `FLUSH_ACK` | Clear ESP32 ring buffer on track skip/seek. |

---

## 🔍 Troubleshooting

| Symptom | Cause | Fix |
| :--- | :--- | :--- |
| **No sound on PCM5102A** | `SCK` floating, `XSMT` low, or wrong mode | Tie `SCK → GND`, `XSMT → 3.3V`, and select **PCM5102A** in the app (pins `27 / 14 / 22`). |
| **No sound / weak sound on Built-in DAC** | Unamped headphones or wrong pins | Connect `GPIO 25`, `GPIO 26`, and `GND` to an amplifier/AUX input. |
| **Hotspot not found** | 5 GHz hotspot enabled | ESP32-WROOM-32 supports **2.4 GHz Wi-Fi only**. |
| **Static / screech after skip** | Stale firmware or misaligned frames | Flash the latest [`firmware_wifi_softap.ino`](firmware_wifi_softap/firmware_wifi_softap.ino) (includes `FLUSH` + 8-byte alignment). |

---

## 🙏 Credits & Acknowledgements

The Android client in this repository is built upon **Stash**, originally created, designed, and architected by **Rawnald Clark** ([@rawnaldclark](https://github.com/rawnaldclark) · `rawnaldclark@gmail.com`), including the multi-module Clean Architecture, Compose UI, Room database, Media3/ExoPlayer service, signal-path diagnostics, gapless crossfade, and streaming integrations.

---

## 📜 License

```text
Copyright (C) 2026 Gokul E. & Contributors
Portions of this software are derived from Stash,
originally developed by Rawnald Clark.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
```

Licensed under the **GNU General Public License v3.0** — see [`LICENSE`](LICENSE) for details.
