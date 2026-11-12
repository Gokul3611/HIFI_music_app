<div align="center">

# 🎵 Music
### Minimalist, Bit-Perfect Hi-Fi Music Player for Android

[![Download Latest APK](https://img.shields.io/badge/Download_APK-v0.9.124-brightgreen?style=for-the-badge&logo=android)](https://github.com/Gokul3611/HIFI_music_app/releases/download/latest/Music.apk)
[![GitHub Release](https://img.shields.io/github/v/release/Gokul3611/HIFI_music_app?style=for-the-badge&color=blue&label=Latest%20Version)](https://github.com/Gokul3611/HIFI_music_app/releases/latest)
[![License](https://img.shields.io/badge/License-GPL_v3-orange?style=for-the-badge)](LICENSE)

<br/>

**A clean, ad-free, sponsor-free music app designed for audiophiles and everyday music lovers.**  
Stream online, play your local offline music library, read synced word-by-word lyrics, and stream 24-bit/96 kHz bit-perfect audio directly to external DACs.

---

[**Direct Download (.apk)**](https://github.com/Gokul3611/HIFI_music_app/releases/download/latest/Music.apk) • [**Installation Guide**](#-quick-install-guide-for-android) • [**Features**](#-features) • [**ESP32 DAC Setup**](#-esp32-hi-fi-direct-streaming)

</div>

---

## 📥 Quick Install Guide for Android

Sharing with friends? It only takes **30 seconds** to install:

1. **Download:** Tap [**Music.apk**](https://github.com/Gokul3611/HIFI_music_app/releases/download/latest/Music.apk) on your Android phone.
2. **Open:** Once downloaded, open your notification drawer or Downloads folder and tap `Music.apk`.
3. **Allow Installation:**
   - If Android shows *"For your security, your phone is not allowed to install unknown apps from this source"*, tap **Settings** $\to$ toggle **Allow from this source**.
   - If Google Play Protect shows a prompt, tap **More details** $\to$ **Install anyway** (standard for open-source apps built directly from source).
4. **Enjoy:** Tap **Install** and open **Music** from your home screen!

---

## ✨ Features

- **🚫 Zero Distractions:** 100% free, zero advertisements, no donation tickers, no subscription prompts.
- **⚡ One-Tap Online / Offline Mode:** Seamlessly switch between online streaming discovery and your downloaded offline music library with a single top bar toggle.
- **🎤 Word-Synced Lyrics:** Live lyrics that highlight word-by-word automatically with dynamic background ambient glow.
- **🎧 Audiophile Audio Engine:**
  - Bit-perfect 24-bit / 96 kHz audio pipeline with zero Android resampling.
  - Native FLAC, ALAC, Opus, and AAC support.
  - Hardware-grade EQ and loudness control.
- **📡 ESP32 Hi-Fi Direct (Wireless Lossless DAC):**
  - Stream pure, uncompressed 24-bit / 96 kHz PCM audio over Wi-Fi directly to an external ESP32 + PCM5102A 32-bit DAC.
  - Completely mutes phone speakers and sends uncompressed bit-perfect audio directly to your home amplifier or stereo.

---

## 🎛️ ESP32 Hi-Fi Direct Streaming

If you or your friends have an ESP32 microcontroller with a PCM5102A DAC module:

1. Flash the plug-and-play Arduino sketch located in [`firmware_wifi_softap/firmware_wifi_softap.ino`](https://github.com/Gokul3611/HIFI_music_app/tree/master/firmware_wifi_softap).
2. Turn on your phone's Personal Hotspot:
   - **Hotspot Name:** `Stash-Hotspot`
   - **Password:** `12345678`
3. Power on the ESP32. It will connect to your phone's hotspot automatically.
4. In the **Music** app: Go to **Settings** $\to$ **Audio & Quality** $\to$ **Output** $\to$ choose **ESP32 Hi-Fi Direct**.
5. Pure, uncompressed 24-bit / 96 kHz master-quality sound will instantly stream to your amplifier!

---

## 📄 License
This project is open-source under the GNU General Public License v3.0 (GPLv3).
