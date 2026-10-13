![OpenIPC logo][logo]

# OpenIPC Decoder

**English** | [Русский](README.ru.md) | [简体中文](README.zh-CN.md)

[![Build](https://github.com/OpenIPC/decoder/actions/workflows/build.yml/badge.svg)](https://github.com/OpenIPC/decoder/actions/workflows/build.yml)
[![Release](https://img.shields.io/badge/download-latest-blue)](https://github.com/OpenIPC/decoder/releases/tag/latest)
[![License](https://img.shields.io/badge/license-Prosperity%203.0-blue)](LICENSE.md)
[![Platform](https://img.shields.io/badge/platform-Android%205.0%2B-brightgreen)](https://developer.android.com/about/versions/lollipop)

A small Android app for watching RTSP streams from IP cameras, with hardware H.264/H.265 decoding. It is built for cameras running [OpenIPC](https://openipc.org) firmware and works on phones, tablets, TVs, set-top boxes and car devices.

<p align="center">
  <img src="screenshots/quad_menu.jpg" alt="Multi-view with eight cameras and the menu" width="640">
</p>

## Download

Ready-to-install builds are in the [**latest** release](https://github.com/OpenIPC/decoder/releases/tag/latest):

| File             | What it is                             |
|------------------|----------------------------------------|
| `decoder-v2.apk` | **Current version**, recommended       |
| `decoder.apk`    | Legacy version from the `basic` branch |

A beta channel is available on [Google Play](https://play.google.com/apps/internaltest/4701231141824898735). To join it, send your e-mail through the [OpenIPC Telegram channels](https://openipc.org/our-channels).

## Features

- **Video:** RTSP playback, hardware H.264/H.265 decoding via MediaCodec, automatic decoder recovery, picture fitted to any screen shape
- **Audio:** AAC (RFC 3640 `mpeg4-generic`, ADTS, MP4A-LATM), Opus, G.711 μ-law/A-law, L16 PCM (mono/stereo); in multi-view, the sound of the selected cell
- **Display:** single camera, or multi-view (Quad) with up to 8 cameras at once in a 2×2 or 3×3 grid
- **Controls:** pinch-to-zoom, pan, double-tap to reset, Android TV remote (Leanback launcher)
- **Network:** RTSP over TCP or UDP (Auto falls back to TCP), RTSP multicast, plain RTP pushed over UDP by the camera (unicast or multicast), Basic and Digest authentication, RTSP keepalive, automatic reconnection with exponential back-off, connection-quality indicator
- **WebUI:** built-in browser for the camera's web interface
- **More:** 8 camera slots, URLs stored encrypted, long-press screenshot, URLs can be shared from other apps

## Requirements

- Android 5.0 (API 21) or higher
- An IP camera with RTSP, or one that pushes RTP over UDP (OpenIPC Majestic `outgoing`), see [Transports](#transports)
- Google services (GAPPS) are **not** required: the app works on AOSP builds without them. WebUI uses the system WebView, so it needs a working WebView package (on some devices this is Google's *Android System WebView*); disabling it affects only WebUI — [#15](https://github.com/OpenIPC/decoder/issues/15)

## Quick start

1. Install the APK from the [latest release](https://github.com/OpenIPC/decoder/releases/tag/latest) and launch the app.
2. Tap the screen, or press **OK** on a remote, to open the menu.
3. Choose a camera number (**1**–**8**), then press **Settings** and enter the camera URL:
   ```
   rtsp://username:password@ip_address:554/stream=0
   ```
   Under the URL you can choose the [transport](#transports); TCP is the default.
4. Press **Save**. The app connects straight away and reconnects automatically if the stream drops.

## Controls

### Menu

| Item         | Action                                                                                                                                                                             |
|--------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **1**–**8**  | Switch to a camera slot. The active slot's number shows the connection quality: green under 100 ms of jitter, yellow 100–300 ms, red above 300 ms. A grey number is an empty slot. |
| **U**        | Show the reserved UDP cell of the 3×3 grid full-screen                                                                                                                             |
| **D**        | Text in the corner of every multi-view cell (resolution, codec, decoded/received fps, key frames, status), or in none                                                        |
| **Q**        | Turn multi-view (Quad) on or off                                                                                                                                                   |
| **Settings** | Edit the URL and the transport of the active slot (single-camera mode only)                                                                                                        |
| **WebUI**    | Open the camera's web interface; credentials come from the RTSP URL (single-camera mode only)                                                                                      |
| **Exit**     | Close the app. The small text next to it is the app version and build hash.                                                                                                        |

### Gestures and keys

| Input                    | Action                                                                                   |
|--------------------------|------------------------------------------------------------------------------------------|
| Tap / **OK** / **Enter** | Open or close the menu                                                                   |
| **Back** / **Esc**       | Close the menu                                                                           |
| Pinch, then drag         | Zoom (up to 5×) and pan, single view                                                     |
| Double tap               | Reset zoom                                                                               |
| Long press               | Single view: save a screenshot to `Pictures/OpenIPC`. Multi-view: play this cell's sound |

### Opening a camera from another app

The app accepts plain-text shares (`ACTION_SEND`, `text/plain`). Sharing an `rtsp://` or `udp://` URL from another app, for example OpenIPC Network, puts it into the active slot. An optional `transport` extra (`tcp`, `udp`, `auto`, `multicast`) sets the slot's transport:

```sh
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT 'rtsp://user:pass@192.168.1.10/stream=0' \
  --es transport udp -n com.openipc.decoder/.Decoder
```

### Multi-view (Quad)

Shows the configured slots in a 2×2 grid, or in a 3×3 grid once any of slots 5–8 is used. The ninth cell of the 3×3 grid is reserved for a camera that pushes RTP over UDP; for now it only shows the address and port to send to, and **U** in the menu opens it full-screen. To watch such a camera today, put a [`udp://` URL](#udp-slots-majestic-outgoing) into any slot. Each cell has its own connection and decoder, and the device's video decoder has a fixed budget: the AG-216 test box, for example, decodes about two 1080p30 streams at full rate. So the grid adapts: cells start on key frames only, then decode every frame in priority order (the cell whose sound plays, then slot order) while the decoder keeps up. Cells beyond that stay on key frames, with the picture updated once per key frame interval (`key frames` in the corner text, menu **D**). The limit is learnt on each device by measurement. Substreams of the cameras, where available, let more cells play at full rate. A cell over the decoder's instance limit shows `Decoder limit: N × H.265`. Some cameras accept only one RTSP client at a time. Long-press a cell to play its audio (long-press it again to mute); the selected cell shows an AUDIO badge and is remembered. Tap any cell to open the menu.

## Transports

Each slot has its own transport, chosen under the URL in **Settings** (or with the `transport` extra of a shared link):

| Transport         | How the video arrives                                    | Notes                                                                                                                                                    |
|-------------------|----------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| **TCP** (default) | RTP inside the RTSP connection                           | Works everywhere, also through NAT and firewalls                                                                                                         |
| **UDP**           | RTP over UDP, a port pair per track                      | Lower latency on a LAN. Lost packets are skipped, not resent. Never falls back                                                                           |
| **Auto**          | UDP, or TCP when UDP does not work                       | Falls back to TCP when the camera refuses UDP or no video arrives within 3 s, then stays on TCP until the slot is changed                                |
| **Multicast**     | RTSP multicast (Android 8.0+), or TCP when it is refused | For servers that offer RTSP multicast. OpenIPC Majestic and XM cameras do not: they reply 400 or 451, and the slot plays over TCP in the same connection |

### UDP slots (Majestic outgoing)

A slot can also receive RTP that the camera pushes by itself, without RTSP. Put a `udp://` URL into the slot instead of `rtsp://`:

| URL                                 | Meaning                                                              |
|-------------------------------------|----------------------------------------------------------------------|
| `udp://@:5600`                      | Listen on port 5600                                                  |
| `udp://@239.1.2.3:5600`             | Join the multicast group 239.1.2.3 on port 5600                      |
| `udp://192.168.1.10@239.1.2.3:5600` | The same, but accept packets from this sender only                   |
| `udp://@:5600?codec=h265&pt=97`     | Set the codec or the video payload type when the defaults do not fit |

The codec is taken from the RTP payload type (96 → H.264, 97 → H.265) unless `codec=` is given, and a codec change on the camera is followed while playing. Video only. Two slots cannot listen on the same port.

On the camera, add the device as an `outgoing` destination in `/etc/majestic.yaml` (then restart Majestic):

```yaml
outgoing:
  servers:
    - udp://192.168.1.20:5600
```

`/api/v1/outgoing.json` on the camera shows whether the destination is live. Majestic can also send to a multicast group (`udp://239.1.2.3:5600`), so several devices can watch one camera. On Wi‑Fi the app holds a multicast lock while it is in a group.

## Debug page (port 8085)

While the app runs it serves its debug information over HTTP on port **8085**, for a browser or `curl` on the same network:

- `http://<device>:8085/` — the snapshot fills the page, with each cell's camera, size, fps and losses (in red) drawn over it; the header shows the version, the device time and uptime;
- `http://<device>:8085/snapshot.jpg` (`?labels=0`: frames only) — a picture laid out like the screen, at the screen's resolution (at most 1920×1080), made from the frames the app shows right now; a cell without video is black with its status. Taken only when asked for; the page refreshes it every 3, 5 or 10 s while the tab is visible;
- `http://<device>:8085/stats.json` — the same as JSON, for scripts;
- `http://<device>:8085/log` — the last 500 lines of the app's log.

Only clients on the local network are answered (loopback, 10/8, 172.16/12, 192.168/16, link-local, IPv6 fc00::/7); others get 403. Logins and passwords are never shown.

## Known limitations

> [!IMPORTANT]
> Each item has an issue — comments, test results and pull requests are welcome.
>
> - **RTSP multicast needs Android 8.0+** and a server that offers it; OpenIPC cameras do not — [#69](https://github.com/OpenIPC/decoder/issues/69)
> - **G.726 audio and MP4A-LATM with in-band configuration (`cpresent=1`) are not supported.** OpenIPC Majestic does not use them, and cameras that offer G.726 can usually send G.711 or AAC instead — [#14](https://github.com/OpenIPC/decoder/issues/14)
> - **Credentials are sent in clear text** over plain RTSP/HTTP (Basic auth unless the camera asks for Digest); RTSPS is not supported and not planned — [#16](https://github.com/OpenIPC/decoder/issues/16)
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

| Class                                    | Role                                                                                                              |
|------------------------------------------|-------------------------------------------------------------------------------------------------------------------|
| `Decoder`                                | Main activity: UI, lifecycle, settings, single-view video decoding, multi-view grid                               |
| `RtspClient`                             | Single-view stream: RTSP session, NAL assembly, audio, connection quality                                         |
| `RtspSession`                            | One RTSP session over TCP, UDP or multicast: handshake, RTP receive, keepalive, watchdog, reconnect with back-off |
| `RtspAuth`, `Sdp`                        | Basic and Digest authentication; parsing of the camera's session description                                      |
| `Transport`, `UdpReceiver`, `RtpReorder` | Transport negotiation; UDP and multicast receive with a sender filter; packet reordering                          |
| `RtpListener`                            | `udp://` slots: plain RTP pushed by the camera                                                                    |
| `NalAssembler`                           | Rebuilds H.264/H.265 NAL units from RTP packets, drops units damaged by packet loss                               |
| `MediaCodecManager`, `DecoderRetry`      | Hardware video decoder lifecycle and recovery after decoder errors                                                |
| `AudioManager`, `AudioConfig`            | Audio pipeline: G.711/L16 conversion, AAC/Opus decoding, AudioTrack playback                                      |
| `QuadCell`                               | One multi-view cell: its own session, decoder and optional audio                                                  |
| `MenuManager`                            | Popup menu, URL editor, WebUI browser                                                                             |
| `CredentialStore`, `KeystoreCipher`      | Encrypted storage of camera URLs (Android Keystore)                                                               |
| `FramePool`, `Frame`                     | Buffer pool that reduces garbage-collector load                                                                   |
| `App`, `Crash`                           | Uncaught-exception handler and crash report screen                                                                |
| `DebugServer`, `NetInfo`                 | Debug page on port 8085 (LAN clients only), device addresses                                                      |

Network, video decoding and audio run on separate threads, so a slow decoder never blocks the socket.

## Security notes

- Credentials are taken from the URL and sent in the `Authorization` header: Basic first, Digest when the camera asks for it. They are not included in the RTSP request line.
- RTSP, RTP over UDP and HTTP are not encrypted, and RTSPS is not supported. Credentials that a camera expects inside the URL path or query (for example `user=…_password=…`) are sent with every request. Use the app only on trusted networks, see [#16](https://github.com/OpenIPC/decoder/issues/16).
- Camera URLs, including passwords, are stored encrypted (AES-256-GCM with a key kept in the Android Keystore, Android 6.0+) in the app's private storage. On Android 5.x, or if the Keystore is unavailable, they are stored in plain text. Backup is disabled (`allowBackup="false"`).
- The debug page on port 8085 is not encrypted and has no password; it answers only local-network clients and never shows logins or passwords.

## Device compatibility

Tested the app on your device? Please [**submit a Device report**](https://github.com/OpenIPC/decoder/issues/new?template=device_report.yml). It takes a minute and helps other users.

### Verified devices

Most devices below were reported with the legacy 1.0x version (`basic` branch); the *App* column shows the version that was tested. New reports should include the app version.

| Type        | Device                                 | Android                           | Kernel         | App     | Notes                                                                                      |
|-------------|----------------------------------------|-----------------------------------|----------------|---------|--------------------------------------------------------------------------------------------|
| Phone       | Asus ZC553KL                           | 8.1                               | 3.18.71        | 1.0x    |                                                                                            |
| Phone       | Blackview BV4900Pro                    | 12                                | 4.19.191       | 1.0x    |                                                                                            |
| Phone       | Oppo 5X                                | ColorOS 15.0                      | 5.15.149       | 1.0x    |                                                                                            |
| Phone       | Oppo A17                               | ColorOS 12.1                      | 4.19.191       | 1.0x    |                                                                                            |
| Phone       | Samsung Galaxy A51                     | 13                                | 4.14.113       | 1.0x    |                                                                                            |
| Phone       | Samsung Galaxy M21 (SM-M215F/DSN)      | 12                                | 4.14.113       | 1.0x    |                                                                                            |
| Phone       | Samsung Galaxy S25                     | 15                                | 6.6.30         | 1.0x    |                                                                                            |
| Phone       | Samsung A55 5G                         | 14                                | 6.1.93         | 1.0x    |                                                                                            |
| Phone       | Samsung Galaxy J7                      | 10                                | 3.18.150       | 1.0x    |                                                                                            |
| Phone       | Samsung S8                             | 9                                 | 4.4.153        | 1.0x    |                                                                                            |
| Phone       | Samsung S23 Ultra                      | 15                                | 5.15.153       | 1.0x    |                                                                                            |
| Phone       | Xiaomi Redmi 7A                        | 10                                | 4.9.261        | 1.0x    |                                                                                            |
| Phone       | Xiaomi Redmi Note 7 (M1901F7G)         | 10                                | 4.4.192        | 1.0x    |                                                                                            |
| Phone       | Xiaomi Redmi Note 13 4G                | 13                                | 5.15.94        | 1.0x    |                                                                                            |
| Tablet      | Lenovo TB-X304L                        | 8.1.0                             | 3.18.71        | 1.0x    | Picture cropped in 1.0x, fixed in v2 ([#17](https://github.com/OpenIPC/decoder/issues/17)) |
| Tablet      | Lenovo TB-X606F                        | 10                                | —              | 1.0x    | Picture cropped in 1.0x, fixed in v2 ([#17](https://github.com/OpenIPC/decoder/issues/17)) |
| Tablet      | Lenovo Tab P11                         | 11                                | 4.19.157-perf+ | 1.0x    | Picture cropped in 1.0x, fixed in v2 ([#17](https://github.com/OpenIPC/decoder/issues/17)) |
| Tablet      | Samsung Galaxy Tab A                   | 7.1.1                             | 3.10.49        | 1.0x    |                                                                                            |
| Tablet      | Samsung Galaxy Tab A                   | 11                                | 4.9.227        | 1.0x    |                                                                                            |
| Tablet      | Samsung Tab A7 (SM-T505)               | 12                                | 4.19.157-perf- | 1.0x    |                                                                                            |
| Set-top box | A95X F3 AIR                            | 9 (SlimBOXtv AOSP 9.14, no GAPPS) | 4.9.113        | 1.0x    |                                                                                            |
| Set-top box | IE X3 Air                              | 9 (SlimBOXtv AOSP 9.14, no GAPPS) | 4.9.113        | 1.0x    |                                                                                            |
| Set-top box | AG-216                                 | 10                                | 4.9.170        | v2 1.28 | v2 test device; decoder budget measured at ≈ 1080p60 in total (two 1080p30 at full rate)   |
| Set-top box | SberBox SBDV-00001                     | custom                            | 4.9.228        | 1.0x    |                                                                                            |
| Set-top box | X96Q (Allwinner H313)                  | 10                                | 4.9.170        | 1.0x    |                                                                                            |
| TV          | Haier Candy Android TV 2K              | 11                                | 4.9.243+       | 1.0x    | No icon in the device's app list                                                           |
| TV          | Yandex TV Novex NVX-55U169TSY          | 7.1.1                             | 4.4.3          | 1.0x    |                                                                                            |
| Car         | Media Center JCAC10003 (ac8227/ac8229) | 12                                | 3.18.79+       | 1.0x    |                                                                                            |
| Car         | Mirror Z55                             | 8.1.0                             | 4.4.83         | 1.0x    |                                                                                            |

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

[logo]: https://raw.githubusercontent.com/OpenIPC/.github/refs/heads/main/assets/openipc-logo-black.svg
