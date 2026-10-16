# Debug

- [Debug server](#debug-server)
- [Page](#page)
- [Snapshot](#snapshot)
- [Stream checks in the single view](#stream-checks-in-the-single-view)
- [stats.json fields](#statsjson-fields)
- [Log](#log)
- [Save report](#save-report)
- [Corner text in multi-view](#corner-text-in-multi-view)
- [Bug reports](#bug-reports)

## Debug server

While the app runs it serves its debug information over HTTP on port **8085**, for a browser or `curl` on the same network. It is always on and read-only.

| Path | Content |
|---|---|
| `/` (or `/index.html`) | the [page](#page): snapshot with the text of each cell |
| `/stats.json` | the state as JSON, pretty-printed, see [fields](#statsjson-fields) |
| `/snapshot.jpg` | a picture of the screen, see [Snapshot](#snapshot); `?labels=0`: frames only |
| `/log` | the last 500 lines of the app's log, see [Log](#log) |
| `/report.txt` | a header, `/stats.json` and `/log` in one file, downloaded as `report-YYYYMMDD-HHMMSS.txt`, see [Save report](#save-report) |

- Only clients on the local network are answered; others get `403`. The rule and what is hidden: [security.md](security.md#debug-server).
- `GET` only (`405` otherwise), one request per connection, `Cache-Control: no-store`.
- Two worker threads at background priority, a short queue, a 20 s deadline per request: the server never gets in the way of decoding; a busy server drops the connection.
- If port 8085 is taken, the server retries every 10 s; `server` in `/stats.json` says why.
- On a phone that is only on a camera's or toy's Wi‑Fi, open the pages on the phone itself: `http://127.0.0.1:8085/`.

The device's addresses are in `addresses` of `/stats.json`, and the [U cell](usage.md#the-u-cell) shows them while it waits for a stream.

<p align="center">
  <img src="../screenshots/debug_page.jpg" alt="Debug page: snapshot with each cell's text" width="640"><br>
  <img src="../screenshots/debug_json.png" alt="/stats.json" width="318">
  <img src="../screenshots/debug_log.png" alt="/log" width="318">
</p>

## Page

`http://<device>:8085/`

- The snapshot fills the page, laid out like the screen, with each cell's slot, camera host, transport, size, fps and losses (in red) drawn over it. A decoded rate below 90 % of the received one is shown in orange.
- The header shows a status dot, the version and git hash, the device time and the uptime, and links to **JSON**, **Log** and **Save report** (`/report.txt`).
- **Info On / Off** hides the text over the cells (and the list below them on a narrow screen), leaving only the picture. It is **Off** until switched on; the choice is remembered in the browser.
- **Snapshot 3 s / 5 s / 10 s**: how often the picture is refreshed (default 5 s). `/stats.json` is fetched every 1.5 s. Both only while the tab is visible.
- On a screen narrower than 720 px the snapshot takes the full width and the cells are listed below it.

| Status dot / header note | Meaning |
|---|---|
| green | the app answers |
| orange, `UI not responding within 1000 ms` | the UI thread is stuck (an ANR); only the device facts are reported |
| orange, `no activity` / `App in background` | no activity behind the server, or the app is paused |
| red, `video decoder not responding — restart the device` | `decoder_stuck`: frames arrive in several cells, none is decoded for 20 s ([#145](https://github.com/OpenIPC/decoder/issues/145)) |
| red, `offline` | the page cannot reach the app |

## Snapshot

`http://<device>:8085/snapshot.jpg`

A JPEG laid out like the screen, at the screen's resolution (at most 1920 wide, at least 640), made from the frames the app shows now. A cell without video is black with its status. With labels (the default) the text of each cell is drawn in; `?labels=0` gives the frames only, which is what the page uses. It is taken only when asked for and reused for 1 s, so several viewers cost one capture. `503` when there is nothing to show. Needs Android 7.0+ (PixelCopy).

## Stream checks in the single view

In the single view (and the U view) the page and the snapshot still show the whole grid ([#100](https://github.com/OpenIPC/decoder/issues/100)): the camera on screen is framed in blue, and every other configured slot gets a short stream check while the page is open. Its cell says what was found, for example `Stream OK · H.265 · 25 fps · 3.1 Mbit/s · checked 12 s ago`, or the error.

- A check connects, receives packets for about 3 s and does not decode.
- Each slot is checked again about every 45 s, at most two checks run at a time, one new check starts every 2 s.
- Checks stop when no one has asked for the page for 60 s.
- Not checked: a slot of the same camera (host and port) as the one on screen, since some cameras take one client only; `udp://` slots (the port would be taken); the U cell.
- In `/stats.json` these cells are under `slots`, with `probe`.

## stats.json fields

`http://<device>:8085/stats.json`. Logins and passwords are redacted in every string. Numbers that are not integers have two decimals; NaN and infinity are `null`.

### Top level

| Field | Type | Meaning |
|---|---|---|
| `app` | string | `OpenIPC Decoder` |
| `version` | string | versionName, e.g. `2.10` |
| `git` | string | short commit hash of the build |
| `time` | string | device time, `yyyy-MM-dd HH:mm:ss` |
| `uptime_s` | number | seconds since the device booted |
| `device` | string | manufacturer, model, SoC (Android 12+; else `Build.HARDWARE`) and board |
| `android` | string | release and SDK level, e.g. `10 (SDK 29)` |
| `screen` | string | display size in pixels, `WxH` |
| `addresses` | list | `http://<ip>:8085/` for every address of the device |
| `server` | string | `listening`, or why port 8085 could not be opened |
| `decoders` | list or `"loading"` | the device's video decoders, see [decoders](#decoders) |
| `ui_thread` | string | `ok`, or `not responding within 1000 ms` (then only the fields above are present) |
| `mode` | string | `single`, `multi-view`, `slides` (multi-view with Slides on), `udp` (U cell full-screen), `paused` (app in background); `no activity` when no activity is attached |
| `active_slot` | number | active slot, 1–8 |
| `decoder` | object | multi-view only: `capacity` (macroblocks/s), `learnt` (bool), `failed_load` (macroblocks/s, 0: none), see [architecture.md](architecture.md#decode-planner) |
| `decoder_stuck` | bool | multi-view only: frames arrive in at least two cells and none is decoded for 20 s |
| `udp` | object | U view only: `port` (5600) and `addresses` (the device's IPs to send to) |
| `single` | object | single view only, see [single](#single) |
| `cells` | list | multi-view and U view, see [cells](#cells) |
| `slots` | list | single view and U view: every grid cell with the stream checks, see [slots](#slots) |

### decoders

One entry per video decoder and MIME type (encoders are left out).

| Field | Meaning |
|---|---|
| `name` | codec name as Android lists it |
| `type` | MIME type, e.g. `video/hevc` |
| `hardware` | hardware-accelerated (Android 10+) |
| `adaptive` | supports adaptive playback |
| `max_instances` | instances the codec declares (Android 6+) |
| `fps_1080p` | highest declared frame rate at 1920×1080, when 1080p is supported |

### single

| Field | Meaning |
|---|---|
| `url` | the slot URL, redacted |
| `transport` | in use, as in [cells](#cells) |
| `transport_mode` | the slot's setting: `TCP`, `UDP`, `AUTO`, `MULTICAST` |
| `video` | a frame was shown within the last 2 s |
| `status` | the status text on screen, or the session state |
| `size` | `WxH`, once known |
| `codec` | `H.264`, `H.265` or `MJPEG` |
| `audio` | audio codec: `NONE`, `AAC`, `LATM`, `OPUS`, `PCMU`, `PCMA`, `PCM` |
| `out_fps` | frames shown in the last second |
| `decode` | `full`, or `key frames` with Slides |

### cells

One entry per grid cell; `slot` is 1–8 or `U`. An empty cell has only `slot`, `video: false` and `text: "not configured"`.

| Field | Meaning |
|---|---|
| `slot` | 1–8, or `U` |
| `video` | the cell showed a frame within the last 2 s |
| `text` | the corner text of the cell, see [below](#corner-text-in-multi-view) |
| `url` | the slot URL, redacted (`udp://@:5600` for U) |
| `transport` | in use: `TCP`, `UDP`, `multicast`, `HTTP`, `UDP push`, `multicast push`; after a fallback `TCP (auto)` or `TCP (multicast)`; the mode alone before anything plays |
| `transport_mode` | the slot's setting (`UDP` for U) |
| `out_fps` | frames decoded per second |
| `in_fps` | frames received per second |
| `lost_fps` | frames per second the decoder lost (queue full, no input buffer); network losses do not count |
| `nal_dropped` | NAL units dropped because one of their packets was lost |
| `nal_damaged` | slices passed on cut short at a lost piece; absent while damaged decoding is off |
| `decode` | `full` or `key frames` |
| `load` | macroblocks/s the cell costs in its current mode, once size and rate are known |
| `paused` | paused by the decode planner (`Paused: decoder overloaded`) |
| `audio` | this cell's sound plays |

### slots

Single view and U view: every cell of the grid as the debug page shows it (3×3 in the U view or when the active slot is 5–8).

| Field | Meaning |
|---|---|
| `slot` | 1–8, or `U` |
| `on_screen` | this is the camera on screen |
| `url`, `transport_mode` | as in [cells](#cells), for configured slots only |
| `transport` | on screen: in use; otherwise what a check goes over (`check over TCP` for TCP and Auto slots, `check over UDP`, `check over multicast`, `UDP listen`, `HTTP`, `UDP push` for U) |
| `video`, `text` | on screen: as it plays; otherwise `false` and the check result or why there is none |
| `probe` | not on screen: the check, see below |

| `probe` field | Meaning |
|---|---|
| `state` | `ok`, `error`, `checking`, `waiting`, `not checked` |
| `checked_s_ago` | seconds since the last check began |
| `summary` | result text |
| `codec`, `size` | when found |
| `fps`, `kbps`, `key_frames` | measured during the check |

The FLOW-UFO test build ([toys.md](toys.md#flow-ufo)) adds a `flowufo` object per cell and in `single` (`jieli` for `jieli://` slots, [toys.md](toys.md#hf--jieli-toys)), and a `heartbeat` object for RTSP slots that send the KY UFO heartbeat, and `rotate` (degrees counter-clockwise) where MJPEG frames are turned ([toys.md](toys.md#ky-ufo-rtsp-toys)); it is not in the releases.

## Log

`http://<device>:8085/log`: the last 500 lines of the app's own log (logcat in `threadtime` format; without the `READ_LOGS` permission logcat returns only the app's own lines), redacted as they come in. The same lines over adb: `adb logcat -s OpenIPCDecoder`.

## Save report

One file with everything a bug report needs, the same text from the app and from the debug page:

```
OpenIPC Decoder report
App: OpenIPC Decoder 2.11 (36), git <hash>
Device: <manufacturer> <model>
Android: <version> (SDK <n>)
Time: 2026-10-15 12:00:00 +0300
Uptime: 1h 2m 3s

== stats.json ==
{ ...the same object as /stats.json, pretty-printed... }

== log ==
...the last 500 lines of /log...
```

- **In the app**: a **long press on Debug** in the menu (on a remote: hold **OK** on Debug, or press **Menu** while Debug has the focus). The file is saved to `Download/OpenIPC/report-YYYYMMDD-HHMMSS.txt`, a toast shows its name, and the system *Share* sheet opens to send it (Telegram, mail). Where no app can take it, as on most TV boxes, there is no share sheet; the file stays in `Download/OpenIPC` (over adb: `adb pull /sdcard/Download/OpenIPC/`).
- Android 10 and newer save through MediaStore and need no permission. Android 6–9 ask for the storage permission first; refused, the file goes to the app's own folder (`Android/data/com.openipc.decoder/files/Download/`), and the share sheet gets the text (cut at 50 000 characters) instead of the file.
- **On the debug page**: **Save report** in the header, or `curl -OJ http://<device>:8085/report.txt`. Local-network clients only, like the other paths.
- Redacted like `/log` and `/stats.json`, and the whole text once more: no logins or passwords.

## Corner text in multi-view

**Debug** in the menu shows it in every cell; the same text is `text` in `cells`.

Examples: `1920×1080 H.265 25/25 fps` at full rate, `1920×1080 H.265 key frames · 0.5 fps` on key frames only.

| Part | Meaning |
|---|---|
| `1920×1080 H.265` | stream size and codec (`H.264`, `H.265`, `MJPEG`) |
| `25/25 fps` | full rate: decoded / received frames per second |
| `key frames · 0.5 fps` | key frames only, with the frames shown per second (MJPEG: `reduced`, one frame per second) |
| `· software` | key frames on a software decoder, beyond the hardware instance limit (MJPEG: BitmapFactory) |
| `· damaged` | frames with lost pieces are decoded with the damage concealed |
| `Connecting…`, `Buffering…`, `Disconnected` | the session state before video arrives |
| `Decoder limit: N × H.265` | no decoder instance left, not even a software one |
| `Paused: decoder overloaded` | paused by the decode planner, tried again later |

## Bug reports

Send, in a [GitHub issue](https://github.com/OpenIPC/decoder/issues):

1. App version and git hash (menu, under **Exit**), device model and Android version.
2. A [report file](#save-report) saved while the problem is visible (or after about 30 s of trying): a long press on **Debug** in the menu, or **Save report** on the debug page. Attach the `.txt` file to the issue. Without it, `/stats.json` and `/log`.
3. The camera: make, model, firmware, URL **without the password** (`user:***`), codec, resolution, transport.
4. A screenshot or `/snapshot.jpg` if the picture is wrong.

The [camera report](https://github.com/OpenIPC/decoder/issues/new?template=camera_report.yml) and [device report](https://github.com/OpenIPC/decoder/issues/new?template=device_report.yml) forms ask for the same. Check that no password or public IP address is in what you post.
