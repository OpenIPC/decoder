<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://openipc.org/assets/openipc-logo-white.svg">
  <source media="(prefers-color-scheme: light)" srcset="https://openipc.org/assets/openipc-logo-black.svg">
  <img alt="OpenIPC logo" src="https://openipc.org/assets/openipc-logo-black.svg">
</picture>

# OpenIPC Decoder

**English** | [Русский](README.ru.md) | [简体中文](README.zh-CN.md)

[![Build](https://github.com/OpenIPC/decoder/actions/workflows/build.yml/badge.svg)](https://github.com/OpenIPC/decoder/actions/workflows/build.yml)
[![Release](https://img.shields.io/badge/download-latest-blue)](https://github.com/OpenIPC/decoder/releases/tag/latest)
[![License](https://img.shields.io/badge/license-Prosperity%203.0-blue)](LICENSE.md)
[![Platform](https://img.shields.io/badge/platform-Android%205.0%2B-brightgreen)](https://developer.android.com/about/versions/lollipop)

A small Android app for watching RTSP streams from IP cameras, with hardware H.264/H.265 decoding. It is built for cameras running [OpenIPC](https://openipc.org) firmware and works on phones, tablets, TVs, set-top boxes and car devices.

<p align="center">
  <img src="screenshots/photo_menu.jpg" alt="Application screenshot" width="640">
</p>

## Download

Ready-to-install builds are in the [**latest** release](https://github.com/OpenIPC/decoder/releases/tag/latest):

| File             | What it is                                                  |
|------------------|-------------------------------------------------------------|
| `decoder-v2.apk` | **Current version**, recommended                            |
| `decoder.apk`    | Legacy version from the `basic` branch                      |
| `*.aab`          | Android App Bundles for Google Play; not for manual install |

A beta channel is available on [Google Play](https://play.google.com/apps/internaltest/4701231141824898735). To join it, send your e-mail through the [OpenIPC Telegram channels](https://openipc.org/our-channels).

## Features

- **Video:** RTSP playback, hardware H.264/H.265 decoding via MediaCodec
- **Audio:** AAC (RFC 3640 `mpeg4-generic`, ADTS), G.711 μ-law/A-law, L16 PCM (mono/stereo)
- **Display:** single camera, or Quad mode with 4 cameras at once
- **Controls:** pinch-to-zoom, pan, double-tap to reset, Android TV remote (Leanback launcher)
- **Network:** Basic and Digest authentication, RTSP keepalive, automatic reconnection with exponential back-off, connection-quality indicator
- **WebUI:** built-in browser for the camera's web interface
- **More:** long-press screenshot, 4 camera slots, URLs can be shared from other apps

## Requirements

- Android 5.0 (API 21) or higher
- An IP camera with RTSP over TCP (see [Known limitations](#known-limitations))

## Quick start

1. Install the APK from the [latest release](https://github.com/OpenIPC/decoder/releases/tag/latest) and launch the app.
2. Tap the screen, or press **OK** on a remote, to open the menu.
3. Choose a camera number (**1**–**4**), then press **Settings** and enter the camera URL:
   ```
   rtsp://username:password@ip_address:554/stream=0
   ```
4. Press **Save**. The app connects straight away and reconnects automatically if the stream drops.

## Controls

### Menu

| Item         | Action                                                                                                                                                                             |
|--------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Q**        | Turn Quad mode on or off                                                                                                                                                           |
| **1**–**4**  | Switch to a camera slot. The active slot's number shows the connection quality: green under 100 ms of jitter, yellow 100–300 ms, red above 300 ms. A grey number is an empty slot. |
| **Settings** | Edit the RTSP URL of the active slot (single-camera mode only)                                                                                                                     |
| **WebUI**    | Open the camera's web interface; credentials come from the RTSP URL (single-camera mode only)                                                                                      |
| **Exit**     | Close the app. The small text next to it is the app version and build hash.                                                                                                        |

### Gestures and keys

| Input                    | Action                                  |
|--------------------------|-----------------------------------------|
| Tap / **OK** / **Enter** | Open or close the menu                  |
| **Back** / **Esc**       | Close the menu                          |
| Pinch, then drag         | Zoom (up to 5×) and pan                 |
| Double tap               | Reset zoom                              |
| Long press               | Save a screenshot to `Pictures/OpenIPC` |

### Opening a camera from another app

The app accepts plain-text shares (`ACTION_SEND`, `text/plain`). Sharing an `rtsp://` URL from another app, for example OpenIPC Network, puts it into the active slot.

### Quad mode

Shows all configured slots in a 2×2 grid. Each cell has its own connection and decoder. Long-press a cell to play its audio (long-press it again to mute); the selected cell shows an AUDIO badge and is remembered. Tap any cell to open the menu.

## Known limitations

> [!IMPORTANT]
> These are known gaps that we plan to address. Each one has an issue — comments, test results and pull requests are welcome.
>
> - **RTSP over TCP only.** No UDP or multicast — [#7](https://github.com/OpenIPC/decoder/issues/7)
> - **Only 4 camera slots** — [#13](https://github.com/OpenIPC/decoder/issues/13)
> - **Audio formats not supported:** MP4A-LATM, Opus, G.726, and AAC frames split across RTP packets — [#14](https://github.com/OpenIPC/decoder/issues/14)
> - **Possible dependency on Google services (GAPPS)** on some devices; under investigation, so disable GAPPS with care — [#15](https://github.com/OpenIPC/decoder/issues/15)
> - **Credentials are sent in clear text** over plain RTSP/HTTP (Basic auth unless the camera asks for Digest); RTSPS is not supported — [#16](https://github.com/OpenIPC/decoder/issues/16)
>
> Known bugs are tracked separately under the [`bug` label](https://github.com/OpenIPC/decoder/issues?q=is%3Aissue+is%3Aopen+label%3Abug).

## Branches

| Branch  | Status                                                                                                         |
|---------|----------------------------------------------------------------------------------------------------------------|
| `v2`    | **Main development branch** (default). Modular code in `android/`. Builds are published as `decoder-v2.apk`.   |
| `basic` | Legacy single-file version (1.0x), kept for reference and older setups. Builds are published as `decoder.apk`. |

Please base pull requests on `v2`.

## Building

You need JDK 17 and the Android SDK (platform 36). Point the build at the SDK with `ANDROID_HOME` or `android/local.properties`.

```bash
cd android
./gradlew assembleDebug        # debug build, no signing keys needed
# → app/build/outputs/apk/debug/app-debug.apk
```

A release build needs a keystore at `android/key.jks` and these environment variables:

```bash
export KEYSTORE_PASS=your_password
export KEY_ALIAS=your_alias
export KEY_PASS=your_key_password
./gradlew assembleRelease      # → app/build/outputs/apk/release/app-release.apk
```

Debug and release builds are signed with different keys. To switch from one to the other, uninstall the app first; its saved camera URLs are removed with it.

**CI:** every push is built by GitHub Actions. Pushes to `v2` and `basic` update the [latest](https://github.com/OpenIPC/decoder/releases/tag/latest) release and notify the OpenIPC Telegram channel. Other branches only attach the APK as a workflow artifact on the run page.

## Architecture

| Class                         | Role                                                                    |
|-------------------------------|-------------------------------------------------------------------------|
| `Decoder`                     | Main activity: UI, lifecycle, settings, video decode thread             |
| `RtspClient`                  | RTSP handshake, RTP-over-TCP receive loop, watchdog, SDP parsing        |
| `NalAssembler`                | Rebuilds H.264/H.265 NAL units from RTP fragmentation units             |
| `MediaCodecManager`           | Hardware video decoder lifecycle                                        |
| `AudioManager`, `AudioConfig` | Audio pipeline: G.711/L16 conversion, AAC decoding, AudioTrack playback |
| `QuadCell`                    | A self-contained stream (network, watchdog, decoder) for one Quad cell  |
| `MenuManager`                 | Popup menu, URL editor, WebUI browser                                   |
| `FramePool`                   | Buffer pool that reduces garbage-collector load                         |

Network, video decoding and audio run on separate threads, so a slow decoder never blocks the socket.

## Security notes

- Credentials are taken from the URL and sent in the `Authorization` header: Basic first, Digest when the camera asks for it. They are not included in the RTSP request line.
- Plain RTSP and HTTP are not encrypted. Use the app only on trusted networks, see [#16](https://github.com/OpenIPC/decoder/issues/16).
- Camera URLs, including passwords, are stored encrypted (AES-256-GCM with a key kept in the Android Keystore, Android 6.0+; on Android 5.x they are stored as before) in the app's private storage. Backup is disabled (`allowBackup="false"`).

## Device compatibility

Tested the app on your device? Please [**submit a Device report**](https://github.com/OpenIPC/decoder/issues/new?template=device_report.yml). It takes a minute and helps other users.

### Verified devices

The devices below were reported with the legacy 1.0x version (`basic` branch). New reports should include the app version.

| Type        | Device                                 | Android                           | Kernel         | App  | Notes                                                                |
|-------------|----------------------------------------|-----------------------------------|----------------|------|----------------------------------------------------------------------|
| Phone       | Asus ZC553KL                           | 8.1                               | 3.18.71        | 1.0x |                                                                      |
| Phone       | Blackview BV4900Pro                    | 12                                | 4.19.191       | 1.0x |                                                                      |
| Phone       | Oppo 5X                                | ColorOS 15.0                      | 5.15.149       | 1.0x |                                                                      |
| Phone       | Oppo A17                               | ColorOS 12.1                      | 4.19.191       | 1.0x |                                                                      |
| Phone       | Samsung Galaxy A51                     | 13                                | 4.14.113       | 1.0x |                                                                      |
| Phone       | Samsung Galaxy M21 (SM-M215F/DSN)      | 12                                | 4.14.113       | 1.0x |                                                                      |
| Phone       | Samsung Galaxy S25                     | 15                                | 6.6.30         | 1.0x |                                                                      |
| Phone       | Samsung A55 5G                         | 14                                | 6.1.93         | 1.0x |                                                                      |
| Phone       | Samsung Galaxy J7                      | 10                                | 3.18.150       | 1.0x |                                                                      |
| Phone       | Samsung S8                             | 9                                 | 4.4.153        | 1.0x |                                                                      |
| Phone       | Samsung S23 Ultra                      | 15                                | 5.15.153       | 1.0x |                                                                      |
| Phone       | Xiaomi Redmi 7A                        | 10                                | 4.9.261        | 1.0x |                                                                      |
| Phone       | Xiaomi Redmi Note 7 (M1901F7G)         | 10                                | 4.4.192        | 1.0x |                                                                      |
| Phone       | Xiaomi Redmi Note 13 4G                | 13                                | 5.15.94        | 1.0x |                                                                      |
| Tablet      | Lenovo TB-X304L                        | 8.1.0                             | 3.18.71        | 1.0x | Picture cropped, [#17](https://github.com/OpenIPC/decoder/issues/17) |
| Tablet      | Lenovo TB-X606F                        | 10                                | —              | 1.0x | Picture cropped, [#17](https://github.com/OpenIPC/decoder/issues/17) |
| Tablet      | Lenovo Tab P11                         | 11                                | 4.19.157-perf+ | 1.0x | Picture cropped, [#17](https://github.com/OpenIPC/decoder/issues/17) |
| Tablet      | Samsung Galaxy Tab A                   | 7.1.1                             | 3.10.49        | 1.0x |                                                                      |
| Tablet      | Samsung Galaxy Tab A                   | 11                                | 4.9.227        | 1.0x |                                                                      |
| Tablet      | Samsung Tab A7 (SM-T505)               | 12                                | 4.19.157-perf- | 1.0x |                                                                      |
| Set-top box | A95X F3 AIR                            | 9 (SlimBOXtv AOSP 9.14, no GAPPS) | 4.9.113        | 1.0x |                                                                      |
| Set-top box | IE X3 Air                              | 9 (SlimBOXtv AOSP 9.14, no GAPPS) | 4.9.113        | 1.0x |                                                                      |
| Set-top box | SberBox SBDV-00001                     | custom                            | 4.9.228        | 1.0x |                                                                      |
| Set-top box | X96Q (Allwinner H313)                  | 10                                | 4.9.170        | 1.0x |                                                                      |
| TV          | Haier Candy Android TV 2K              | 11                                | 4.9.243+       | 1.0x | No icon in the device's app list                                     |
| TV          | Yandex TV Novex NVX-55U169TSY          | 7.1.1                             | 4.4.3          | 1.0x |                                                                      |
| Car         | Media Center JCAC10003 (ac8227/ac8229) | 12                                | 3.18.79+       | 1.0x |                                                                      |
| Car         | Mirror Z55                             | 8.1.0                             | 4.4.83         | 1.0x |                                                                      |

### Not working

| Type        | Device         | Android | Kernel  | App  | Notes    |
|-------------|----------------|---------|---------|------|----------|
| Set-top box | Unnamed TV box | 5.1.1   | 3.14.29 | 1.0x | No video |

## Version history

Changes are listed on the [releases page](https://github.com/OpenIPC/decoder/releases) and in the [commit history](https://github.com/OpenIPC/decoder/commits/v2). The history of 1.0x versions is in the [`basic` branch README](https://github.com/OpenIPC/decoder/tree/basic#version-history).

## License

[The Prosperity Public License 3.0.0](LICENSE.md): free for noncommercial use, with a 30-day trial for commercial use. See [LICENSE.md](LICENSE.md) for the full terms.

## Support

- Questions and bug reports: [GitHub Issues](https://github.com/OpenIPC/decoder/issues)
- Documentation: [openipc.org](https://openipc.org)

[![Telegram](https://openipc.org/images/telegram_button.svg)](https://openipc.org/our-channels)
