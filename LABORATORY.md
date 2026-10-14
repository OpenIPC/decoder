# Laboratory

The test bench for on-device testing of the decoder: devices, cameras, what each of them can do, how to reach them and how to test. This file holds the things that **change rarely**. Plans, checklists and run results stay in the test-round issues.

| Goes here | Goes into the test-round issue |
|---|---|
| devices, cameras, IP addresses, firmware, codecs | which PRs and builds are tested in this round |
| what a camera or server supports (transports, audio codecs, limits) | checklist (`- [ ]`) |
| access, tools, scripts, pitfalls | one comment per run: build, commit, result table, findings |
| log lines and how to read them | open questions to check on the bench |

**Keep it current.** If a test round finds that something here is wrong or has changed (new camera, new firmware, a camera broke, a new pitfall), fix this file in the same round and mention the change in the run comment.

**Never publish credentials.** No passwords, no full RTSP URLs with credentials, no screenshots with people in the frame. Ask in the round's issue if you need them.

## Test rounds

| Round | Issue | Scope |
|---|---|---|
| 1 | [#53](https://github.com/OpenIPC/decoder/issues/53) (closed, reference) | v2 lifecycle, video fit, audio formats, Quad audio, credential store (#48–#52, #54) |
| 2 | [#74](https://github.com/OpenIPC/decoder/issues/74) | transports: RTSP over UDP, plain RTP, RTSP multicast (#70, #72, #73) |

A new round opens a new issue with the same structure as #53, links to this file for the bench and adds a row to the table above.

## Rules

- **CI builds only.** Test the APKs built by GitHub Actions: workflow artifacts for working branches, the `latest` release for `v2` / `basic`. No local builds.
- Check the commit hash of the installed build in the menu (`Exit [v…, <hash>]`).
- Leave a build with working cameras installed on the device when you finish.
- Findings that are not quick fixes get their own issues (time-box about 15 minutes per problem).
- When every item for a PR is ticked: comment on the PR with a link to the result, take it out of draft, remove `awaiting device test`.

## Devices

### AG-216 Android TV boxes

Two identical boxes with the same firmware (checked 2026-10-13):

| | Aceline-1 | Aceline-2 |
|---|---|---|
| Address | `172.17.32.144:5555` (Ethernet; Wi‑Fi switched off) | `172.17.32.145:5555` (Ethernet) |
| Display | **none** (headless): only `screencap`, the debug page and the logs show what is on screen | **monitor** Philips 226VL over HDMI (HDMI audio active); for checks that need a real picture or a person looking |
| Serial | `KFB442005119` | `KFB442001288` |
| Extra apps | Yandex Browser TV, Faceter, Record Radio | VK TV, Wink, VLC, Nova; autostart `com.oranth.atvapplist` |
| Role | test box: builds of working branches, 8-camera 3×3 layout below | **always the latest `v2` release** (updated after every merge into `v2`), for a look at the current app; multi-view with two cameras: slot 1 `.133`, slot 2 `.165` |

Common to both:

| | |
|---|---|
| Access | ADB over TCP on port 5555, no authorization prompt, shell uid 2000 (no root) |
| Model | `AG-216` (`ro.product.brand`/`manufacturer` say Amlogic, product `p281`); the SoC is **Allwinner** `sun50iw9p1` (H313/H616 family), platform `cupid` / `homlet` |
| Firmware | `eros_p1-userdebug 10 QP1A.191105.004 251022 release-keys`, built 2025-10-22, kernel 4.9.170, security patch 2019-12-05 |
| Fingerprint | `Amlogic/p281/p281:10/QP1A.191105.004/251022:userdebug/release-keys` |
| Android | 10 (API 29), `armeabi-v7a` (32-bit userspace on a 64-bit CPU) |
| RAM / storage | 2 GB / `/data` 11 GB |
| Screen | 1280×720 (16:9), 60 Hz, 160 dpi, `television` UI mode, no touch |
| HW video decoder | `OMX.allwinner.video.decoder.avc` (16 instances), `…hevc` (**4** instances); beyond that, key-frame cells use `c2.android.*` software decoders (#138) |
| JPEG decoder | `OMX.allwinner.video.decoder.mjpeg` is declared in `/vendor/etc/media_codecs.xml` but not listed by Android, and creating it fails (`0xfffffffe`): MJPEG always runs on the software path here |
| Decoder hang | Once (2026-10-13, after a long test session) every cell received but nothing decoded until `adb reboot`; the app now says so (#145, #147). In tests: all cells `0.0 fps` with `in_fps` > 0 → reboot the box first |
| Quirks | The vendor firmware sometimes shows its own "Update Alert!!" dialog after screen off/on; unrelated to the app |

Check the firmware of a box: `$ADB shell getprop ro.build.description`. If it changes, update the table above.

#### Developer settings

Both boxes are kept in sync (aligned on 2026-10-13). Check with `$ADB shell settings get <namespace> <key>`:

| Setting | Value | Why |
|---|---|---|
| Developer options, ADB | on | |
| `global stay_on_while_plugged_in` | `3`; screen-off timeout never | the screen never sleeps during a run |
| Animation scales | 1.0 | |
| `system pointer_location`, `system show_touches` | 0 | the pointer overlay would be drawn on top of the app and end up in screenshots |
| `secure anr_show_background` | 1 | ANR dialogs of background apps are shown (#76) |
| `global package_verifier_enable`, `verifier_verify_adb_installs` | 0, 0 | `adb install` is not verified (no delay, no prompt on screen) |
| `global hdmi_control_enabled` / `hdmi_control_auto_device_off_enabled` | 1 / 0 | CEC on, but the box does not switch the display off |
| Locale | `en-GB` | system UI and app strings in English, also in screenshots |

Set without root: `settings put …` for the keys; the locale via Settings → Language (`am start -a android.settings.LOCALE_SETTINGS`, D-pad to *English (United Kingdom)*). Left as they are, not used by the app: `debug_view_attributes`, `force_allow_on_external`, `force_resizable_activities` (on only on Aceline-1).

Not available in the lab yet: a phone or tablet (rotation, #31), a Wi‑Fi Android device (multicast lock), Android 5.

## Cameras

All cameras are on the lab network `172.17.32.0/24` and reachable from the device.

### Group A: stream tests

Only these cameras are used for protocol, codec, audio and transport tests.

| IP | Hardware / firmware | Video | Audio | RTSP server | Notes |
|---|---|---|---|---|---|
| `172.17.32.133` | OpenIPC, SigmaStar SSC33x (infinity6b0), Majestic Lite | H.264 | Opus | `OpenIPC.org RTSP Server/0.1` | OSD "The OpenIPC Lab."; codec and audio are changed live in tests |
| `172.17.32.126` | OpenIPC, SigmaStar SSC33x (infinity6b0), sensor SC2336P, Majestic Lite | H.265 1920×1088 | Opus 48 kHz stereo | `OpenIPC.org RTSP Server/0.1` | Also has an ONVIF metadata track |
| `172.17.32.53` | XM, stock firmware | H.264 1280×720 (coded 1280×736), 25 fps | none | `H264DVR 1.0` | **One RTSP client at a time**; login and password are in the URL path |

Default state is listed above; a round that changes a camera's codec should put it back or note the new state here.

### Group B: grid fillers

Only for filling grid cells (Quad 2×2, 3×3) and screenshots; not used in stream tests.

| IP | Video | State |
|---|---|---|
| `172.17.32.201` | H.264 | OK; substream is off (`stream=1` returns an empty stream) |
| `172.17.32.184` | H.265 | OK |
| `172.17.32.167` | | OK |
| `172.17.32.247` | | OK |
| `172.17.32.248` | | OK |
| `172.17.32.165` | H.265 1920×1080, 30 fps | OK since 2026-10-13 (SSC335, sc2336; firmware 2.6.10.12 lite). Before, on the 2024-10 firmware, the encoder produced no frames: SDP without parameter sets (`profile-level-id=000000`), `MI_VENC … ring height is zero` in `dmesg`; fixed by the firmware update |

### URLs

- OpenIPC cameras: `rtsp://<user>:<password>@<ip>/stream=0` (main), `/stream=1` (substream).
- MJPEG (Majestic): RTSP `rtsp://<user>:<password>@<ip>/stream=2` (RTP/JPEG, `.133`: 1280×720, 5 fps), HTTP `http://<user>:<password>@<ip>/mjpeg` (`multipart/x-mixed-replace`, chunked, Basic auth).
- XM: `rtsp://<ip>:554/user=<user>_password=<password>_channel=1_stream=0.sdp?real_stream`. The password is in the path, so it also appears in the `Content-Base` header; the app redacts it in logs (#55).

### 3×3 layout (README screenshots, 8-slot tests)

| Slot | Camera |
|---|---|
| 1 | `.247` |
| 2 | `.133`, HTTP MJPEG `http://<.133>/mjpeg` (#121, #139) |
| 3 | `.126` |
| 4 | `.201` |
| 5 | `.248` |
| 6 | `.184` |
| 7 | `.167` |
| 8 | `.53` (XM, bottom right) |

The ninth cell is the U cell (pushed UDP RTP, #87). Its source on the bench: `.165` pushes its main stream with Majestic `outgoing` to `udp://172.17.32.144:5600` (Aceline-1); `.165` is also a spare filler for any slot.

### Other sources

| What | Where | Notes |
|---|---|---|
| MJPEG over RTSP (#119) | `rtsp://<.133>/stream=2` | RTP/JPEG PT 26, type 0 (4:2:2), Q = 255 (tables in-band), 1280×720, 5 fps, ≈ 5 Mbit/s, plus Opus |
| HTTP MJPEG (#121) | `http://<.133>/mjpeg` | `multipart/x-mixed-replace; boundary=boundarydonotcross`, Basic auth |
| Pushed RTP | `.165` → Aceline-1 `:5600` | the U cell; check with `curl -u … http://172.17.32.165/api/v1/outgoing.json` (`state: live`) |

## What the servers support

### RTSP transports

Checked with raw RTSP `SETUP` requests (2026-10-12).

| Request (`Transport:`) | Majestic Lite (`.133`, `.126`) | XM `H264DVR 1.0` (`.53`) |
|---|---|---|
| `RTP/AVP/TCP;interleaved` | 200 | 200 |
| `RTP/AVP;unicast;client_port=X-Y` | 200, `RTP/AVP/UDP;unicast;…;server_port` | 200, `RTP/AVP;unicast;mode=PLAY;source=…;server_port=…;ssrc=…` (no `/UDP`) |
| `RTP/AVP;multicast` (with or without `destination`) | **400** `client_port' not found` | **451** |
| `RTP/AVP;multicast;client_port=…` | 200, but silently unicast | 200, but silently unicast |

No camera in the lab does RTSP multicast. To test #69 for real, run a multicast-capable RTSP server on the lab host (e.g. mediamtx relaying `.133`).

Multicast from OpenIPC is only available as plain RTP via Majestic `outgoing` (`udp://<group>:<port>`), see #68.

### RTP over UDP from the lab cameras

Measured on the lab host (8 s of RTSP-over-UDP video each, #113): the cameras send the packets **heavily reordered** (up to ~42 positions, p99 34 — the app's reorder buffer handles that), and `.126` also **loses** about 1.5 packets/s on the way (`.133`: almost none). A 1080p key frame is a few hundred packets, so over UDP key frames often arrive with a hole and are dropped whole: on key frames only (multi-view) such a cell shows nothing. Auto then falls back to TCP (#125, #128); pushed streams (`udp://`, the U cell) cannot (#133). Over TCP nothing is lost. The AG-216 caps the socket receive buffer at 256 KiB (`rmem_max`), which is not the cause.

### SDP

- Majestic: `c=IN IP4 0.0.0.0`; tracks `a=control:video`, `audio`, `metadata`; Opus PT 98, ONVIF metadata PT 107.
- XM: `c=IN IP4 172.17.32.53`; one track `a=control:trackID=3`, video H.264 only.

### Audio codecs (Majestic Lite on the lab cameras)

| `.audio.codec` | RTP | Status |
|---|---|---|
| `opus` | `opus/48000/2` | ✅ supported |
| `aac` | `mpeg4-generic/8000/1` | ✅ supported |
| PCM | `L16/8000/1` | ✅ supported |
| G.711 μ-law / A-law | | ❌ falls back to L16 on this build; G.711 cannot be tested here |

### Changing the camera on the fly (OpenIPC)

```sh
ssh root@172.17.32.133
cli -s .video0.codec h265        # h264 | h265
cli -s .audio.codec opus         # opus | aac | ...
/etc/init.d/S95majestic restart
```

Plain RTP (`outgoing`): add a destination such as `udp://172.17.32.144:5600` (or a group `udp://239.1.2.3:5600`) in the web UI → Outgoing, or via `/api/v1/outgoing.json`.

Rebooting a test camera or restarting Majestic on it is fine when it misbehaves.

## Access

### adb

`adb` is not installed system-wide on the lab host. Download Google's platform-tools to a temp dir:

```sh
curl -sSLO https://dl.google.com/android/repository/platform-tools-latest-linux.zip
unzip -q platform-tools-latest-linux.zip
$PWD/platform-tools/adb connect 172.17.32.144:5555   # Aceline-1, no display
$PWD/platform-tools/adb connect 172.17.32.145:5555   # Aceline-2, with a monitor
export ADB="$PWD/platform-tools/adb -s 172.17.32.144:5555"
$ADB shell getprop ro.product.model   # AG-216
```

### Builds

- `v2` / `basic`: `gh release download latest -R OpenIPC/decoder -p decoder-v2.apk -p decoder.apk`
- A given 2.xx version: `gh release download v2.01 -R OpenIPC/decoder` (`decoder-v2-2.01.apk`). CI creates the release `v<versionName>` on the first v2 build of each version (#157).
- Branches: `build.yml` builds signed APKs only on **push** runs (not `pull_request`); artifact name is `decoder-<branch with / → ->`:
  ```sh
  B=feat/rtsp-multicast
  RUN=$(gh run list -R OpenIPC/decoder -b $B -e push -L 1 --json databaseId -q '.[0].databaseId')
  gh run download $RUN -R OpenIPC/decoder -n decoder-${B//\//-}
  ```
- Versions: `v2` is **2.xx** (from 2.00, #152). Every PR that changes the app bumps `android/app/build.gradle` as its last commit before the merge: `versionName` +0.01 (2.00 → 2.01 → …), `versionCode` +1. Docs-only PRs do not. `basic` keeps its 1.07.x line. The installed version shows in the menu (`Exit [v…]`) and in `/stats.json`.
- Every build is `com.openipc.decoder` with the release signature. Switching from a debug build needs `adb uninstall` first, which wipes the settings.

### Setting a camera without the UI

```sh
$ADB shell "am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT '<rtsp url>' [--es transport tcp|udp|auto|multicast] \
  -n com.openipc.decoder/.Decoder"
```

The link goes into the active slot and is saved. The transport extra exists from #70 on.

- Transport in the UI: menu → URL dialog of the slot → **TCP / UDP / Auto / Multicast** under the URL.
- udp:// slots (#72) are a URL, not a transport: `udp://@:5600`, `udp://@239.1.2.3:5600`, `udp://<camera ip>@239.1.2.3:5600`, optional `?codec=h265&pt=97`.
- The share intent writes into the **active** slot (and switches to the single view): select the slot first.
- Filling slots on the box: tap a menu button by its text rather than by coordinates (the row is `1 2 3 4 5 6 7 8 U D Q S`): `uiautomator dump`, take the `bounds` of `text="N"`, `input tap` its centre; the URL dialog of a slot: **Settings** (single view only) → `TCP` / `UDP` / `Auto` / `Multicast` → `SAVE`.

## Methods

### Smoke test

Install → set the camera via the share intent → 15 s → screenshot → 3× (Home → back via the launcher) → screenshot → logcat. Pass: picture after every resume, thread count does not grow, 0 `FATAL EXCEPTION` / `ANR in`.

<details><summary><code>smoke.sh &lt;apk&gt; &lt;label&gt;</code> (needs <code>ADB</code> and <code>CAM_URL</code> in the environment)</summary>

```bash
#!/bin/bash
# usage: ADB="adb -s 172.17.32.144:5555" CAM_URL='rtsp://...' ./smoke.sh <apk> <label>
PKG=com.openipc.decoder; APK=$1; L=$2; OUT=res/$L; mkdir -p $OUT
mask(){ sed -E 's#password=[^_ ]+#password=***#g; s#(rtsp://[^:/@]+:)[^@]*@#\1***@#g'; }
$ADB uninstall $PKG >/dev/null 2>&1
echo "[$L] install: $($ADB install -r -t "$APK" 2>&1 | tail -1)"
echo "[$L] version: $($ADB shell dumpsys package $PKG | grep -m1 versionName | tr -d ' ')"
$ADB logcat -c
$ADB shell "am start -W -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT '$CAM_URL' -n $PKG/.Decoder" | grep -E "Status|TotalTime" | tr '\n' ' '; echo
sleep 15
PID=$($ADB shell pidof $PKG); echo "[$L] pid=$PID threads=$($ADB shell ls /proc/$PID/task | wc -l) top=$($ADB shell dumpsys activity activities | grep -m1 mResumedActivity | awk '{print $4}')"
$ADB exec-out screencap -p > $OUT/1_start.png
for i in 1 2 3; do   # Home -> back, 3 times
  $ADB shell input keyevent KEYCODE_HOME; sleep 3
  $ADB shell am start -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -n $PKG/.Decoder >/dev/null; sleep 8
done
PID2=$($ADB shell pidof $PKG); echo "[$L] after 3x resume: pid=$PID2 threads=$($ADB shell ls /proc/$PID2/task | wc -l) top=$($ADB shell dumpsys activity activities | grep -m1 mResumedActivity | awk '{print $4}')"
$ADB exec-out screencap -p > $OUT/2_resume.png
$ADB logcat -d | mask > $OUT/logcat.txt
echo "[$L] decoder starts=$(grep -c 'Start video decoder' $OUT/logcat.txt) fatal=$(grep -c 'FATAL EXCEPTION' $OUT/logcat.txt) anr=$(grep -c 'ANR in' $OUT/logcat.txt)"
grep -E 'FATAL EXCEPTION|ANR in' -A8 $OUT/logcat.txt | head -20
```
</details>

The script uninstalls first. To test an upgrade that keeps the settings, use `$ADB install -r` without the uninstall.

### Other checks

| What | How |
|---|---|
| Thread leak | `$ADB shell ls /proc/$(pidof com.openipc.decoder)/task \| wc -l`, before and after |
| UDP socket leak | `$ADB shell cat /proc/$(pidof com.openipc.decoder)/net/udp`, count lines before and after |
| CPU per thread | `$ADB shell top -H -p <pid>` (a spinning `rtsp-audio` thread was found this way) |
| Screen off / on | `input keyevent KEYCODE_SLEEP` / `KEYCODE_WAKEUP` |
| Grid ↔ single stress (#76) | loop: menu → tap `4` (grid → single), 4 s, menu → tap `Q` (single → grid), 8 s; afterwards count `Davey! duration=` ≥ 1000 ms and `ANR in` in logcat; 8–10 cycles reproduce #76 today |
| Transport in use | `/stats.json`: `transport` (in use, e.g. `TCP (auto)` after a fallback) and `transport_mode` (setting) per cell; `nal_dropped` = NAL units lost to packet loss |
| Menu with a remote (#91) | `input keyevent KEYCODE_DPAD_CENTER` opens the menu with the focus on the active item (slot, Q or U); `DPAD_RIGHT` ×n, `DPAD_DOWN` (Exit), `DPAD_UP`, then `DPAD_CENTER` must switch to that slot; `KEYCODE_BACK` closes it. The focused item: `uiautomator dump /sdcard/m.xml` and look for `focused="true"`. Check in single view and multi-view |
| 4:3 screen | `wm size 960x720`, back with `wm size reset` |
| Audio playback (sound cannot be heard remotely) | `dumpsys media.audio_flinger`: server position of the app's track; frames/s ≈ sample rate, no underruns |
| Screenshot | `$ADB exec-out screencap -p > shot.png`; long-press screenshot in the app saves to `Pictures/OpenIPC/` |
| Load average on SigmaStar cameras | ~10–11 is normal on SSC33x (the vendor pipeline threads sit in `D` state); not a sign of a fault |
| UI frozen? | `dumpsys gfxinfo com.openipc.decoder`: frame count must grow |
| Block UDP from a camera (no root on the box) | on the camera: `iptables -A OUTPUT -p udp --sport 1024: -j DROP`, remove afterwards; if the camera has no `iptables`, mark as not testable |
| Installed build | menu → `Exit [v…, <hash>]`, or `dumpsys package com.openipc.decoder \| grep versionName` |

### Log lines (`logcat -s OpenIPCDecoder`)

| Line | Meaning |
|---|---|
| `Start video decoder` | decoder (re)started; count per run |
| `playing over UDP` / `TCP` / `multicast` | transport in use after PLAY |
| `UDP SETUP failed (…), using TCP`, `no video over UDP, using TCP`, `multicast SETUP failed (…)` | Auto / Multicast fallback |
| `listening for RTP on UDP port …, group …` | udp:// slot bound |
| `RTP payload type 96, H.264` | udp:// codec picked from the stream |
| `joined 239.x.x.x:port` | RTSP multicast group joined |
| `UDP channel …: N packets lost, M late or duplicate` | reorder stats when a session ends |
| `UDP from an unexpected sender ignored` | sender filter |
| `…: packets but no decoded frame for 8 s (N NAL units lost) over UDP, using TCP` | Auto: UDP delivered, but nothing decodable (#125) |
| `audio SETUP over UDP failed (…), using TCP`, `audio not over UDP like video, using TCP` | Auto: UDP audio not possible, both tracks over TCP (#128) |
| `NAL unit dropped (…): packet loss inside a fragmented NAL unit` | a NAL unit with a lost packet (logged 1st–3rd, then every 100th) |
| `Video pt 26 MJPEG`, `JPEG frame dropped (…)` | MJPEG stream (#119) / a JPEG frame with a lost packet |
| `Decoder capacity of this device: … blocks/s, failed at …` | multi-view planner learnt a new capacity (#85, #126) |

### Pitfalls

- Do not restart with `am force-stop` + `am start`: the TV launcher grabs focus about 1 s later. Use Home → back.
- Count `FATAL EXCEPTION` only; plain `AndroidRuntime` lines come from the `input` / `am` shell commands.
- Mask the camera password in every log you post (the `mask` function in the smoke script).
- The XM camera serves one RTSP client at a time: a session that is not torn down blocks the next connection.
- Switching multi-view ↔ single view can freeze the window for 3–68 s on the AG-216 (#76, open): `Davey! … QueueBufferDuration=…` in logcat, sometimes an ANR. After many switches `media.codec` once hung in `D` state (load ~70) — reboot the box (`adb reboot`, ~25 s). Avoid needless switches in tests; start the app straight into the wanted mode (a cold start with the share intent opens the single view).
- `/stats.json` `ui_thread: not responding within 1000 ms` means the UI thread is stuck — usually #76.
