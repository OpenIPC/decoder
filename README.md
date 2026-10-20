![OpenIPC logo][logo]

# OpenIPC Decoder

**English** | [Русский](README.ru.md) | [简体中文](README.zh-CN.md)

[![Build](https://github.com/OpenIPC/decoder/actions/workflows/build.yml/badge.svg)](https://github.com/OpenIPC/decoder/actions/workflows/build.yml)
[![Release](https://img.shields.io/badge/download-latest-blue)](https://github.com/OpenIPC/decoder/releases/latest)
[![License](https://img.shields.io/badge/license-Prosperity%203.0-blue)](LICENSE.md)
[![Platform](https://img.shields.io/badge/platform-Android%205.0%2B-brightgreen)](https://developer.android.com/about/versions/lollipop)

A small Android app for watching RTSP streams from IP cameras, with hardware H.264/H.265 decoding. It is built for cameras running [OpenIPC](https://openipc.org) firmware and works on phones, tablets, TVs, set-top boxes and car devices.

- **Video:** H.264/H.265 via MediaCodec, MJPEG over RTSP and HTTP; automatic decoder recovery
- **Audio:** AAC, Opus, G.711, L16
- **Views:** one camera, or multi-view with up to 8 cameras plus a camera that pushes RTP, decoding adapted to what the device can do
- **Network:** RTSP over TCP, UDP (Auto falls back to TCP) or multicast, RTP pushed by the camera (Majestic `outgoing`), Basic and Digest authentication, automatic reconnection
- **More:** Android TV remote, zoom, camera web interface, URLs stored encrypted, a debug page on port 8085

<p align="center">
  <img src="screenshots/quad_menu.jpg" alt="Multi-view with eight cameras and the menu" width="640">
</p>

<p align="center">
  <img src="screenshots/single_status.jpg" alt="Single view right after connecting: transport, codecs and bitrate" width="640">
</p>

<p align="center">
  <img src="screenshots/phone_menu.jpg" alt="The menu on a phone" width="400">
  <img src="screenshots/phone_webui.jpg" alt="WebUI on a phone: the page scrolls the field above the keyboard" width="400">
</p>

<p align="center">
  <img src="screenshots/debug_page.jpg" alt="Debug page: snapshot with each cell's text" width="640"><br>
  <img src="screenshots/debug_json.png" alt="/stats.json" width="318">
  <img src="screenshots/debug_log.png" alt="/log" width="318">
</p>

## Download

Ready-to-install builds are in the one release, [**latest**](https://github.com/OpenIPC/decoder/releases/latest), titled `Decoder <version>`. The file names carry no version, so these links never change:

| File             | What it is                             | Permanent link                                                               |
|------------------|----------------------------------------|------------------------------------------------------------------------------|
| `decoder-v2.apk` | **Current version**, recommended       | <https://github.com/OpenIPC/decoder/releases/latest/download/decoder-v2.apk> |
| `decoder.apk`    | Legacy version from the `basic` branch | <https://github.com/OpenIPC/decoder/releases/latest/download/decoder.apk>    |

Every build is replaced in `latest` by the next one. Development is continuous, so there are no releases per version; every new `v2` version is also posted as its APK, with the commit, to the OpenIPC Telegram channel ([our channels](https://openipc.org/our-channels)).

The change history of every version is in [CHANGELOG.md](CHANGELOG.md).

A beta channel is available on [Google Play](https://play.google.com/apps/internaltest/4701231141824898735). To join it, send your e-mail through the [OpenIPC Telegram channels](https://openipc.org/our-channels).

## Requirements

- Android 5.0 (API 21) or higher
- An IP camera with RTSP or HTTP MJPEG, or one that pushes RTP over UDP (OpenIPC Majestic `outgoing`)
- Google services (GAPPS) are **not** required. WebUI needs a working system WebView ([#15](https://github.com/OpenIPC/decoder/issues/15))

## Quick start

1. Install the APK from the [latest release](https://github.com/OpenIPC/decoder/releases/latest) and launch the app.
2. Tap the screen, or press **OK** on a remote, to open the menu.
3. Choose a camera number (**1**–**8**), then press **Settings** and enter the camera URL:
   ```
   rtsp://username:password@ip_address:554/stream=0
   ```
   Under the URL you can choose the transport; Auto (UDP, falling back to TCP) is the default for a new slot. Below it, **Turn** turns the picture of a camera mounted sideways or upside down, and **Mirror** flips it left to right. Other URL types: [Transports](docs/transports.md).
4. Press **Save**. The app connects straight away and reconnects automatically if the stream drops.

## Controls

```
[ 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 ]
[ Settings | WebUI  | Grid   | Slides ]
[ Exit     | Reset  | Debug  | UDP    ]
```

| Input                 | Action                                                                 |
|-----------------------|------------------------------------------------------------------------|
| Tap / **OK**          | Open or close the menu                                                 |
| **1**–**8**           | Camera slot; the number's colour shows the connection quality          |
| **Grid** / **Slides** | Multi-view or one camera / every frame or key frames only              |
| **Debug** / **UDP**   | Text in the multi-view cells / the camera that pushes RTP to port 5600 |
| Pinch, double tap     | Zoom and pan, reset the zoom (single view)                             |
| Long press            | Screenshot (single view) / play this cell's sound (multi-view)         |

Every item, the remote, sharing a URL from another app and **Reset**: [Usage](docs/usage.md).

## Known limitations

> [!IMPORTANT]
> Each item has an issue — comments, test results and pull requests are welcome.
>
> - **RTSP multicast needs Android 8.0+** and a server that offers it; OpenIPC cameras do not — [#69](https://github.com/OpenIPC/decoder/issues/69)
> - **G.726 audio and MP4A-LATM with in-band configuration (`cpresent=1`) are not supported.** OpenIPC Majestic does not use them, and cameras that offer G.726 can usually send G.711 or AAC instead — [#14](https://github.com/OpenIPC/decoder/issues/14)
> - **MJPEG is decoded in software on devices without a usable JPEG decoder** (the AG-216 lists one but cannot create it) — [#132](https://github.com/OpenIPC/decoder/issues/132)
> - **The video decoder of the AG-216 can stop responding until the device is restarted**: frames arrive, none is decoded; the app says so on the screen and on the debug page — [#145](https://github.com/OpenIPC/decoder/issues/145)
> - **Credentials are sent in clear text** over plain RTSP/HTTP (Basic auth unless the camera asks for Digest); RTSPS is not supported and not planned — [#16](https://github.com/OpenIPC/decoder/issues/16)
> - **Screenshots and the debug snapshot need Android 7.0+**
>
> Known bugs are tracked separately under the [`bug` label](https://github.com/OpenIPC/decoder/issues?q=is%3Aissue+is%3Aopen+label%3Abug).

## Documentation

| Document                                | Contents                                                                                                                                                     |
|-----------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [Usage](docs/usage.md)                  | menu, gestures and keys, remote control, multi-view, Slides, the U cell, WebUI, sharing a URL from another app, screenshots, Reset                           |
| [Transports](docs/transports.md)        | URL formats and options: RTSP over TCP/UDP/Auto/multicast, `udp://` push (Majestic `outgoing`), HTTP MJPEG; codecs, authentication, what the servers support |
| [Debug](docs/debug.md)                  | debug page on port 8085, `/stats.json` field reference, `/log`, snapshots, what to send with a bug report                                                    |
| [Architecture](docs/architecture.md)    | data path, classes, threads, the decode planner, SurfaceView rendering, repository layout                                                                    |
| [Testing and releases](docs/testing.md) | branches, CI, unit tests, versions and releases, branch builds, `mapping.txt`, building locally                                                              |
| [Security](docs/security.md)            | credential store, debug server (LAN only), log redaction, no encryption on the network                                                                       |
| [Devices](docs/devices.md)              | verified and not working devices, hardware decoder notes, device and camera reports                                                                          |
| [Drones and Wi‑Fi toys](docs/toys.md)   | how toys stream, FLOW-UFO, KY UFO and Jieli drones, what to try with an unknown toy, what to send                                                            |

The change history is the [merged pull requests](https://github.com/OpenIPC/decoder/pulls?q=is%3Apr+is%3Amerged+base%3Av2) and the [commits of v2](https://github.com/OpenIPC/decoder/commits/v2).

## Support

- Questions and bug reports: [GitHub Issues](https://github.com/OpenIPC/decoder/issues)
- Tested the app on a device or a camera? Please send a [Device report](https://github.com/OpenIPC/decoder/issues/new?template=device_report.yml) or a [Camera report](https://github.com/OpenIPC/decoder/issues/new?template=camera_report.yml)
- OpenIPC: [openipc.org](https://openipc.org)

[![Telegram](https://openipc.org/images/telegram_button.svg)](https://openipc.org/our-channels)

## License

[The Prosperity Public License 3.0.0](LICENSE.md): free for noncommercial use, with a 30-day trial for commercial use. See [LICENSE.md](LICENSE.md) for the full terms.

[logo]: https://raw.githubusercontent.com/OpenIPC/.github/refs/heads/main/assets/openipc-logo-black.svg
