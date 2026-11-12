# Contributing to Music & Hi-Fi DAC

Thank you for your interest in contributing to **Music**! This document provides guidelines and instructions for contributing to the application and its companion ESP32 firmware.

By contributing to this repository, you agree that your contributions will be licensed under the **GNU General Public License v3.0 (GPL-3.0)**.

---

## 🧭 Code of Conduct & Principles

1. **Zero Compromise on Quality:** We strive for bit-perfect, uncompressed audio fidelity. DSP features must be cleanly bypassable and maintain 24-bit dynamic range without unwanted clipping or resampling.
2. **Privacy First:** The app contains zero tracking, zero analytics SDKs, and zero telemetry. All logins and tokens stay encrypted locally via Android Keystore / Tink.
3. **Clean, Minimalist Aesthetics:** UI elements must be uncluttered, responsive, and purposeful. No advertising, sponsor tickers, or nag screens.

---

## 🛠️ Local Development Environment

### Requirements
- **JDK 17** (Temurin, Microsoft, or Azul Zulu)
- **Android Studio** Hedgehog (2023.1.1) or newer
- **Android SDK 35** (`platforms;android-35`)
- **Android NDK 27** (`ndk;27.0.12077973`)
- **CMake 3.22.1**
- *(Optional for firmware)* **Arduino IDE 2.x** or **ESP-IDF v5.1+**

### Cloning & Building
```bash
# Clone the repository
git clone https://github.com/Gokul3611/HIFI_music_app.git
cd HIFI_music_app

# Build debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew test
```

The compiled debug APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 📁 Repository Structure

```
HIFI_music_app/
├── app/                      # Application orchestrator, Hilt DI graph, launcher activities
├── core/
│   ├── media/                # Audio engine (Media3, Esp32SocketStreamer, PCM conversion)
│   ├── ui/                   # Jetpack Compose design system and custom components
│   ├── data/                 # DataStore repositories (HiResOutputPreference, offline cache)
│   ├── model/                # Core domain models (Track, Album, QualityTier)
│   ├── common/               # Shared utilities, constants, and extensions
│   └── network/              # Fast, low-overhead HTTP / socket networking
├── feature/
│   ├── home/                 # Main home screen, quick Offline/Online toggle
│   ├── settings/             # Settings screen, ESP32 hotspot guide, audio output picker
│   ├── nowplaying/           # Player screen with word-synced lyrics (LRCLIB)
│   └── library/              # Local audio library indexing & playlist management
└── firmware_wifi_softap/     # ESP32 + PCM5102A Arduino sketch (TCP port 8888, 96 kHz / 32-bit I2S)
```

---

## 🚀 Contribution Workflow

1. **Check Existing Issues:** Before starting major work, please search the [GitHub Issues](https://github.com/Gokul3611/HIFI_music_app/issues) to ensure someone else isn't already working on it.
2. **Create a Branch:**
   ```bash
   git checkout -b feature/your-feature-name
   # or
   git checkout -b fix/issue-description
   ```
3. **Write Tests:** If introducing new logic or altering audio conversions, add appropriate unit tests in `:core:media` or the affected module.
4. **Follow Kotlin Conventions:**
   - Use standard Kotlin style (`kotlin.code.style=official`).
   - Keep coroutines structured on appropriate dispatchers (`Dispatchers.IO` for socket/disk, `Dispatchers.Default` for DSP calculations).
5. **Verify Before Pushing:**
   ```bash
   ./gradlew test
   ./gradlew assembleDebug
   ```
6. **Submit a Pull Request:** Open a PR against the `master` branch. Clearly describe the problem solved, testing conducted, and any hardware validated.

---

## 🎯 Open Roadmap & Ideas

We are actively seeking contributions in the following areas:
- **Automatic mDNS Discovery:** Broadcast `_esp32_audio._tcp.local` from the ESP32 so the Android app automatically discovers the DAC without manual IP entry.
- **Parametric EQ & AutoEQ Integration:** Allow importing AutoEQ frequency response corrections for specific headphone models.
- **Lockless DSP Limiter Profiles:** Expand the C/C++ audio limiter for customizable attack, decay, and knee parameters.
- **Desktop Companion Client:** Lightweight cross-platform desktop streamer (Electron/Tauri or Python) targeting the same ESP32 port 8888 receiver.

---

## ⚖️ License
By contributing, you agree that your code will be licensed under the **GNU General Public License v3.0**.
