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

## 2.26.0 — 2026-11-18

- Decoder takes the "brass" look of our apps (#286, brass 1.3.0 of the brand project); the app
  icon and the TV banner stay blue and white:
  - the menu over the video is a brass panel of brass keys with serif labels; an item that is
    on is pressed brass with an amber glow (was an orange fill), the remote's focus an amber
    frame (was light blue), a slot without a camera has dim text; the version stays on Exit;
  - the status line over the video is amber monospace capitals on a dark plate;
  - the dialogs (camera, Start on boot, Reset) are brass panels with a serif title, dark text
    fields, amber radio buttons and check boxes with the amber focus ring, brass buttons;
  - **Network** opens one **Settings** dialog: a **Decoder** section with **Allow remote
    access** (new; off, the debug page is closed and the app opens no port), the page's port
    and password, and a **Camex** section with **Join a camex server** (was "On: connect now
    and at every start"), the tunnel's state and the library's fields;
  - the web page: the title **Decoder** with the version, "Elegant video player", a panel with
    rivets and the plate **Video wall**, a board of rows with lamps (Decoder, Cameras, Screen,
    Camex), brass JSON, Log, Report and switches, the wall in a frame, the camera on screen in
    amber; the tab's title and icon show the worst lamp. `/stats.json` has `camex`.
- The web page's port (Qodo on #285): a Save while the socket was opening is no longer lost; a
  new port is tried at once after all ports failed; the address on screen follows a move; a
  tunnel start waits for the new port after a Save; Network saved off cancels a waiting start.

## 2.25.0 — 2026-11-18

- The web (debug) page's port is set in **Network** → **Web page port** (default 8085, as
  before; empty: 8085). When the port is taken, for example by another of our apps on the same
  device, the page takes the next free one (up to 8094 by default), logs which port and why,
  and shows the real address in `/stats.json`, the debug text and the Network dialog (#284).
- The camex tunnel is told the port the page really got and restarts when it changes, so the
  server's nginx reaches Decoder's own page.
- The camex tunnel is built from camex 4.1.2 (was 4.0.1): from the tunnel, the library lets in
  only connections to the app's own web port, held by the app itself; anything else is refused
  at once, so the server shows an error rather than another app's page (OpenIPC/camex#187).
- `tests/e2e.sh` checks a taken port: the page moves to 8086, through the tunnel too, and 8085
  through the tunnel is refused.

## 2.24.3 — 2026-11-18

- The camex tunnel is built from camex 4.0.1 (was 4.0.0), which fixes the tunnel over TCP:
  a frame body read in pieces lost its first piece, so traffic from the server to the box
  stalled at about 10 KB/s (OpenIPC/camex#180). The client library had the bug too, so a
  TCP tunnel needs this build as well as a 4.0.1 server. UDP is unaffected.
- `tests/e2e.sh` takes `TRANSPORT=TCP` and checks the video rate through the tunnel.

## 2.24.2 — 2026-11-17

- The camex tunnel is built from camex 4.0.0 (was 3.19.0). The Android library is unchanged:
  the client still uses UDP unless TCP is chosen in Network. A camex 4.0.0 server takes UDP
  and TCP clients on the same port by default and keeps a box's address when it changes
  transport.

## 2.24.1 — 2026-11-17

- The snapshot's note in a black cell is shorter and on two lines ("black in the copy:" / "a
  dark picture, or video this device cannot copy"): in 2.24.0 one line was cut off in a 2×2
  grid.

## 2.24.0 — 2026-11-17

- **Network** has a **Web page password** (the last field, empty by default): when set, the page
  on port 8085 and everything under it (`/stats.json`, `/snapshot.jpg`, `/log`, `/report.txt`)
  asks for it (HTTP Basic, any user name), from the local network and through the tunnel alike.
  A wrong one is answered after a second. Kept sealed, like the camera URLs (Android 6.0+); a
  stored password that cannot be read keeps the pages locked until it can be read again, is
  entered again or is removed in Network.
- The **Web port** field is gone: Decoder always tells the camex server its page is on 8085 (it
  needs a camex server 3.19.0 or later).
- The debug page's snapshot says "black in the copy: a dark picture, or video this device does
  not let be copied" in a live cell whose copy comes out black, instead of a black cell: on the SberBox (Amlogic) the screen
  shows two of four cells on hardware video planes, which neither the snapshot nor `screencap`
  can read; which two is up to the device.

## 2.23.0 — 2026-11-17

- **Network** takes camex 3.19.0 (`bundles/camex` moves to the 3.19.0 release): two optional
  fields, **Server timeout** (5 to 3600 seconds, empty: 20; seconds without an answer before the
  tunnel reconnects) and **Web port** (1 to 65535, empty: none; the port of the box's web page
  for nginx on the server, needs a camex server 3.19.0 or later). The tunnel no longer takes a
  route or a subnet wider than a /8 from the server, so a server cannot send all of the box's
  traffic through it: the tunnel only reaches the cameras.

## 2.22.0 — 2026-11-17

- **Network**: a tunnel to the cameras, OpenIPC camex 3.18.0 built in (#272). A row under the
  grid in the menu holds the camex server, the client name, UDP or TCP, the server's public key,
  the pre-shared key (stored sealed, like the camera passwords) and **On** (connect now and at
  every start; off by default). The state is shown in the dialog and in a notification. Android's
  VPN consent: a system dialog once, or `appops set com.openipc.decoder ACTIVATE_VPN allow` over
  adb for a box without a person.
- A camera reached through a VPN tunnel plays over TCP with Auto: the tunnel's MTU is below a
  camera's RTP packets.
- On a TV box the keyboard of a text field opens on OK, not when the remote reaches the field
  (the Network dialog and the camera URL dialog); a phone gets it at once, as before.
- The camex library comes as the `bundles/camex` submodule pinned to the 3.18.0 release; its
  native code is built for arm64-v8a and armeabi-v7a (`-PdecoderAbis=x86_64` for the emulator),
  with the NDK r27c. The APK grows from about 165 KB to about 440 KB.
- `tests/e2e.sh [API]`: Decoder on an x86_64 emulator in Docker plays a camera through a camex
  server and the tunnel, 13 checks (API 21 and 34).

## 2.21.2 — 2026-11-16

- The APK keeps only the English, Russian and Simplified Chinese resources of its libraries,
  the same language set as the other OpenIPC Android apps. The app's own texts are unchanged.

## 2.21.1 — 2026-11-07

- A Lewei toy (`lewei://`) now gets the stop command (3) when the slot stops, on the same
  connection and before it closes, as the vendor app does. Before, the command was never sent
  and the toy saw only the closed connection (#269).

## 2.21.0 — 2026-11-05

- Toy drones with a Lewei camera (vendor app WiFi UFO): the new slot URL `lewei://192.168.0.1`
  asks for their H.264 video on TCP port 7060 and restores the one byte the camera inverts in each
  frame (#262). Checked on a VISUO drone. No flight command is sent.
## 2.20.1 — 2026-11-04

- On Android 5 **Back** closes the open menu again; it did nothing, only OK or Esc closed it
  (#265).

## 2.20.0 — 2026-11-02

- A remote with only a D-pad now reaches the actions that were touch-only (#263). With the
  menu closed: in the single view hold **Up** / **Down** to zoom in and out (down to 1× resets
  it) and the arrows pan; zoom, channel and page keys and **+** / **−** zoom in steps. In
  multi-view the arrows move a yellow frame over the cells. A held **OK**, or **Menu**, does
  what a long press does: a screenshot in the single view, the selected cell's sound on or
  off in multi-view. A remote's **OK** now opens the menu on release.

## 2.19.1 — 2026-11-02

- The OpenIPC brand launcher icon and Android TV banner, shared by the OpenIPC Android apps:
  the white OpenIPC logo on the brand blue (an adaptive icon with a themed layer on Android 8+,
  a rounded square on Android 5–7.1), and a TV banner with the icon and the app name. All
  vector, generated by the brand kit; the old WebP icon is gone (#260).

## 2.19.0 — 2026-10-31

- **Start on boot** in Settings, off by default: the app opens by itself after the device
  starts, for a box that only shows cameras. Android 10+ needs the permission to draw over
  other apps for it; turning the setting on opens that system screen, or shows the adb command
  on devices without one. A firmware autorun, where the device has one, needs nothing from the
  app. `/stats.json` adds `start_on_boot` (#255).
- Opening the app again while it plays (the launcher, a second autostart) no longer restarts
  the streams; only a shared link does (#255).

## 2.18.4 — 2026-10-22

- `udp://` slots and the U cell: the receive thread runs at the audio priority, so a push no
  longer loses datagrams in a loaded 3×3 grid; before, key-frame bursts still overflowed the
  socket buffer while the thread waited for the CPU (#253).

## 2.18.3 — 2026-10-21

- `udp://` slots and the U cell: a thread of its own reads the socket into a ring of 1024
  datagrams, so key-frame bursts no longer overflow the small socket buffer an app gets
  (`rmem_max`) while the decoder or the UI hold up the receive; the log shows the buffer the
  kernel gave. While the U cell receives a push, the other slots get no stream checks, which
  made the box drop datagrams. `/stats.json` adds `udp` to the cell: `rcvbuf`, `received`,
  `ring_dropped`, `rtp_lost` and, where the app may read it, `socket_drops` (#251).

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
