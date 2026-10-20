# Changelog

The version lives in `versionName` in `android/app/build.gradle` (`v2`) and in
`app/build.gradle` (`basic`). Builds are published only as the
[`latest`](https://github.com/OpenIPC/decoder/releases/tag/latest) release, which holds the
newest `v2` and `basic` builds; each new `v2` version also goes to the OpenIPC Telegram channel as its APK. There are no releases
per version (#194). Since #229 the release is titled `Decoder <v2 version>`, its tag is on the
built `v2` commit, its notes give each branch's version and commit, and the Telegram channel gets the APK of each new `v2` version rather than every build. This file is the change history: one section per `v2` version, newest
first, then the legacy `basic` line.

Versions after 2.17 are `MAJOR.MINOR.PATCH` (2.17 is followed by 2.17.1 or 2.18.0; see
`docs/testing.md`, "Versions and releases"); the headings below keep the numbers as they
were released.

## 2.18.2 — 2026-10-20

- Single view: the decoder is set up for the stream's size from its SPS, so streams above
  1080p (e.g. 2304×1296) play on hardware decoders that cannot find the size themselves; a
  hardware decoder that gives no frame at a size it does not declare hands the stream to a
  software one after 5 s. `/stats.json` shows `size` only while a picture is shown, and adds
  `stream_size` and `decoder` (PR #220, #166).

## 2.18.1 — 2026-10-20

- Snapshot (`/snapshot.jpg`, the debug page): the single view's picture keeps its aspect ratio
  in its cell, as on the screen, so a turned (portrait) slot is no longer stretched to 16:9
  (#248).

## 2.18.0 — 2026-10-20

- Mirror in the camera settings, next to Turn: flips the picture left to right in the single
  view and the grid, for H.264, H.265 and MJPEG; a mirrored H.264/H.265 slot is drawn with
  OpenGL ES (PR #246, closes #226).

## 2.17 — 2026-10-17

- Turn in the camera settings (Auto, 0°, 90°, 180°, 270°), stored per slot; H.264/H.265 are
  turned by the decoder (Android 6+), MJPEG by the view; Auto keeps the URL's `?rotate=` and
  the toys' defaults (PR #225, part of #201).

## 2.16 — 2026-10-16

- Jieli YL3 toys: frames built from several pieces in one datagram, no more freezes and
  broken colours (PR #224, closes #187).
- A flaky unit test of the RTSP heartbeat is stable again (PR #227).

## 2.15 — 2026-10-16

- Toy drones: FLOW-UFO (`flowufo://`), KY UFO RTSP (MJPEG turned 270°, `?rotate=`
  overrides it) and Jieli (PR #215, part of #187, closes #200).

## 2.13 — 2026-10-16

- A swipe from the screen edge (pulling the status bar) no longer opens the menu
  (PR #216, #213).

## 2.12 — 2026-10-15

- The system bars stay hidden over the video on Android 15, the debug page shows only LAN
  addresses, the menu no longer leaks over other windows (PR #206, #202, #203, #205).

## 2.11 — 2026-10-15

- Save report: stats and the log in one file, from the app and from the debug page
  (PR #208, #207).

## 2.10 — 2026-10-15

- A transient decoder loss no longer drops the learnt decoding capacity to the floor
  (PR #190, #189).
- Releases: only `latest` and test pre-releases, no release per version (PR #195, #194);
  docs on `mapping.txt` (#193).

## 2.09 — 2026-10-15

- The single view draws on a SurfaceView instead of a TextureView, which stalled on some TV
  boxes (PR #185, #76).

## 2.08 — 2026-10-15

- Smaller APK: launcher icon / TV banner and the debug page (PR #184, part of #181).

## 2.07 — 2026-10-14

- WebUI inside the system bars on Android 15 (PR #180, #169).
- CI keeps the R8 `mapping.txt` of every build and release, to read obfuscated stack traces
  (PR #183, #181).

## 2.06 — 2026-10-14

- The menu as an 8 / 4 / 4 grid with words; the debug page's Info is off by default
  (PR #178, #174, #176, #177).

## 2.05 — 2026-10-14

- Reset in the menu clears all settings after a confirmation; the menu in two columns
  (PR #172, #171, #173).

## 2.04 — 2026-10-14

- The picture size comes from the decoder's crop rectangle: 1080p streams are no longer
  shown as 1920×1088, and the single view keeps the aspect ratio (PR #167, #166).

## 2.03 — 2026-10-14

- The debug page keeps the grid in the single view, with short stream checks (PR #163,
  #100).

## 2.02 — 2026-10-14

- WebUI full screen, resized above the on-screen keyboard (PR #161, #160).
- Issue forms in English, Chinese and Russian; a fixed title for device reports (PR #164).

## 2.01 — 2026-10-13

- S is the playback mode (every frame or key frames only) in every view, Q is the view
  (PR #155, #153).
- A release of its own for every 2.xx version (PR #158, #157); replaced by `latest` only in
  2.10.

## 2.00 — 2026-10-13

- Version 2.00 for the `v2` branch (`versionCode` 25); `basic` stays 1.07.1, so a `v2` build
  installs over it. The version is shown from `versionName` everywhere: the menu, the debug
  page, `/stats.json`, the user agent (PR #154, #152).

## Before 2.00

Versions 1.x of the `v2` line, up to 1.28 (2026-05-04) and the work merged until
2026-10-13 (PRs up to #147): see the git history and the merged pull requests.

## basic (legacy)

`basic` is the original app, `decoder.apk` in the `latest` release; it gets fixes only.

### 1.7.1 — 2026-10-12

- Released as 1.07.1 (written 1.7.1 since #230). Robust RTSP handshake, end-of-stream
  handling, thread lifecycle and SDP parsing (PR #41, #36–#40).

### 1.07 — 2025-11-11

- The WebUI window works on older devices. Earlier versions (1.04–1.06): see "Version
  history" in the README of the `basic` branch.
