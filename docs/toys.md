# Drones and Wi‑Fi toys

- [How such toys stream](#how-such-toys-stream)
- [Connecting](#connecting)
- [Toy families by Wi‑Fi name](#toy-families-by-wifi-name)
- [FLOW-UFO](#flow-ufo)
- [KY UFO RTSP toys](#ky-ufo-rtsp-toys)
- [HF / Jieli toys](#hf--jieli-toys)
- [Credits](#credits)
- [An unknown toy](#an-unknown-toy)
- [What to send in a report](#what-to-send-in-a-report)
- [Tested toys](#tested-toys)

## How such toys stream

Cheap drones, cars and other toys with a Wi‑Fi camera usually work the same way:

```
  toy (access point, e.g. 192.168.169.1)  ◄── Wi‑Fi, no internet ──►  phone (DHCP address from the toy)
        │                                                                 │
        └── video to the client that asked for it, vendor protocol ◄──────┘ the vendor app (or this app)
```

- The toy is its own **access point** with a fixed name (often the chip or product family plus a few hex digits) and a fixed address. The phone joins it and gets an address from the toy.
- There is **no internet** on that network.
- The video is often **not a standard stream**: no RTSP, no plain RTP. Many toys send JPEG frames over UDP in their own framing and start only after the client has sent a request; some also expect regular replies while they stream. A standard player then sees nothing, and this app stays in *Buffering*.
- Flight or drive commands go over the same network. **This app never sends any**: it only asks for and receives video.

Whether a toy can be played here depends on its protocol. Toys that do offer RTSP or HTTP MJPEG play like any camera ([transports.md](transports.md)); others need a transport of their own, like [FLOW-UFO](#flow-ufo) or [HF / Jieli](#hf--jieli-toys).

## Connecting

1. Switch the toy on and join its Wi‑Fi on the phone.
2. Android notices that the network has no internet and may ask *This network has no internet access. Stay connected?* Answer **yes** (and *Don't ask again*). Otherwise the phone may drop back to another Wi‑Fi or route traffic over mobile data.
3. Mobile data may stay on, but some phones then send traffic for the toy's address over mobile data; if nothing arrives, try with mobile data off.
4. Close the vendor app: the toy usually streams to one client only.
5. While the phone is only on the toy's network, open the [debug pages](debug.md) on the phone itself: `http://127.0.0.1:8085/stats.json` and `http://127.0.0.1:8085/log`. Simpler: a **long press on Debug** in the menu [saves a report](debug.md#save-report) (stats and log in one file) to `Download/OpenIPC`; send it later, when the phone has internet again.

## Toy families by Wi‑Fi name

The Wi‑Fi name and the address the phone gets usually tell the family. The toy is almost always the `.1` address of that network, **not** the phone's own address.

| Wi‑Fi name                                                     | Phone gets      | Family                                                   | Slot URL                                                                        | Status                                                                                                                               |
|----------------------------------------------------------------|-----------------|----------------------------------------------------------|---------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------|
| `FLOW-UFO-xxxxxx`, `FLOW_xxxxxx`                               | `192.168.169.x` | FLOW-UFO (BL60x camera; turbodrone calls it `wifi_uav`)  | `flowufo://192.168.169.1` (UDP 8800 and 8801)                                   | in testing ([#187](https://github.com/OpenIPC/decoder/issues/187))                                                                   |
| `FLY-UFO-xxxxxx` and other cooingdv toys (KY UFO, RC UFO apps) | `192.168.1.x`   | KY UFO RTSP                                              | `rtsp://192.168.1.1:7070/webcam` (with the heartbeat to UDP 7099)               | in testing ([#187](https://github.com/OpenIPC/decoder/issues/187))                                                                   |
| `HF_xxxxxxxx`                                                  | `192.168.80.x`  | Jieli / HF (Jieli AC79 camera, vendor app *DVRunning 2*) | `jieli://192.168.80.1` (CTP on TCP 3333, video to the phone's UDP 2224 or 6666) | test build 4: YL3 video on UDP 2224 plays, lower half broken in test build 3 ([#187](https://github.com/OpenIPC/decoder/issues/187)) |

When a `flowufo://` or `jieli://` slot points at the phone itself, or at the network of another family, its status says so (for example *192.168.1.100 is this phone; the toy is usually 192.168.1.1*, *This network looks like a KY UFO RTSP toy: try rtsp://192.168.1.1:7070/webcam* or, on a `192.168.80.x` network, *try jieli://192.168.80.1*).

## FLOW-UFO

|             |                                                                                                                                                                                       |
|-------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Wi‑Fi name  | `FLOW-UFO-xxxxxx`, `FLOW_xxxxxx`                                                                                                                                                      |
| Camera      | BL60x Wi‑Fi camera module (BL608 or BL618 engine)                                                                                                                                     |
| Toy address | `192.168.169.1`, UDP port 8800 (BL608) or 8801 (BL618); the toy answers from port 1234                                                                                                |
| Slot URL    | `flowufo://192.168.169.1:8800` (or `flowufo://` alone)                                                                                                                                |
| Status      | **in testing**, not in the releases: issue [#187](https://github.com/OpenIPC/decoder/issues/187), PR [#188](https://github.com/OpenIPC/decoder/pull/188)                              |
| Test build  | [toys-187-test2](https://github.com/OpenIPC/decoder/releases/tag/toys-187-test2) (2.11 with the FLOW-UFO, Jieli and KY UFO changes; installs over the release and keeps the settings) |

What the toy does, as far as is known:

- It sends nothing until a client sends it a short **probe**. The vendor library starts two engines, BL608 on UDP port 8800 and BL618 on 8801, and keeps the one that delivers. The app does the same: a slot on 8800 (or without a port) probes **both 8800 and 8801** at once, each from a socket of its own, and locks onto the port whose packets arrive first; the acknowledgements then go there. A slot with another port, `8801` included, probes only that port. The probes repeat until video arrives (every 100 ms; 200 ms on 8801), and start again on both ports when the toy goes quiet for 3 s.
- The video is **MJPEG**: each frame is cut into fragments of about 1 KB. The fragments carry the compressed picture without JPEG headers; the app adds standard headers and shows the frames through its normal MJPEG path.
- The client **acknowledges** regularly which fragments of the last frames it has (every 25 ms; on 8801 every 50 ms and only when there is something to acknowledge), and asks for the next frame when the stream pauses. Frames 5 or more behind the newest are left out of the acknowledgement.
- Bytes 0x34/0x35 of each fragment are the main and flow camera status; the stats show them as `camera_status`.
- Video only, no sound. **No flight command is ever sent**: the vendor app and turbodrone put a (neutral) stick command into every acknowledgement; this app leaves that field empty.

URL options, for comparing with what the toy expects (as with `udp://`, separated by `?` and `&`):

| Option           | Values                    | Default    | What it does                                                                                                                                                                                                                                                                                                                                                                                                                                  |
|------------------|---------------------------|------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `qpara` (or `q`) | 8 hex digits              | `324B142D` | the four quality parameters in each acknowledgement; `324B142D` is what turbodrone sends to working drones, `19322348` an older reading of the vendor app                                                                                                                                                                                                                                                                                     |
| `dqt`            | `zigzag`, `raster`, `q50` | `zigzag`   | the quantization tables in the JPEG header the app puts in front of each frame: `zigzag` = standard tables scaled to the quality byte of the fragment, in zig-zag order as the JPEG standard has it; `raster` = unscaled standard tables in raster order, quality ignored (what turbodrone writes, and it shows real drones' frames); `q50` = unscaled, zig-zag. If the picture is there but blocky, too dark/bright or noisy, try the others |

Examples: `flowufo://192.168.169.1:8800`, `flowufo://192.168.169.1:8800?dqt=raster`, `flowufo://192.168.169.1:8800?q=19322348`, `flowufo://192.168.169.1:8801?dqt=q50`.

The implementation was written from a description of the protocol, not tested on a real toy yet. Status texts while it connects: *Waiting for the toy: probing 192.168.169.1:8800*, *Receiving from the toy at …*, *The toy went quiet: probing … again*. The test build adds a `flowufo` object to `/stats.json`: state, the ports probed (`target`), the port and engine that answered (`port`, `engine`), network, `qpara`, `dqt` (e.g. `zigzag Q50` or `raster (unscaled)`), `camera_status`, frame and fragment counters, losses, `lagging_left_out`, resets, errors, and a `hint` when the address looks wrong.

To test: install the test build, join the `FLOW-UFO-…` Wi‑Fi, [stay connected](#connecting), put `flowufo://192.168.169.1:8800` into a slot (**Settings**, or share the text to the app). If there is no picture, or it looks wrong, wait about 30 s and [send a report](#what-to-send-in-a-report) in [#187](https://github.com/OpenIPC/decoder/issues/187).

## KY UFO RTSP toys

Toys of the cooingdv apps (*KY UFO*, *RC UFO* and others), often with a `FLY-UFO-…` Wi‑Fi, give the phone an address in `192.168.1.x` and stream plain RTSP:

```
rtsp://192.168.1.1:7070/webcam
```

Their apps send a heartbeat, the two bytes `01 01`, to UDP port 7099 of the toy every second while the video plays. For an RTSP slot with port 7070 and path `/webcam` (any host) the app does the same: from the address of the RTSP connection, once a second, until the slot stops. Nothing else is sent to port 7099 (no flight command, no "stop" command). Other RTSP slots send no heartbeat unless asked with `?heartbeat=<port>`, e.g. `rtsp://192.168.1.1:554/live?heartbeat=7099`; `?heartbeat=off` turns it off for a `:7070/webcam` slot. The option is the app's own and is not sent to the camera. The log has one line when it starts (*heartbeat 01 01 every 1000 ms to …*), and `/stats.json` a `heartbeat` object (`target`, `running`, `sent`) for such a slot.

The camera of some of these toys (the K200) is mounted sideways: its frames come turned, a mug lies on its side as a portrait strip in a landscape screen. For a `:7070/webcam` slot the app therefore turns the MJPEG frames 270° counter-clockwise (90° clockwise) when drawing them ([#200](https://github.com/OpenIPC/decoder/issues/200)), and fits the turned frame (a 480×640 frame shows as 640×480 and fills a landscape screen). `?rotate=0|90|180|270` (degrees counter-clockwise) overrides it, e.g. `rtsp://192.168.1.1:7070/webcam?rotate=0` for a unit mounted upright, and also turns other RTSP MJPEG slots; any other value is an error. The option is the app's own and is not sent to the camera. It turns MJPEG only: an H.264/H.265 stream is shown as it comes and the log says *rotate= only applies to MJPEG streams for now (#201)*. The turn is in the picture itself, so zoom, the long-press screenshot and the debug snapshot show it turned too. The log has one line when it is active (*MJPEG frames turned 270° counter-clockwise*), and `/stats.json` a `rotate` (degrees) for such a slot; its `size` is the turned one.

Some of these toys use the FLOW-UFO protocol instead (the KY UFO app has both); if RTSP shows nothing, try `flowufo://192.168.1.1`.

## HF / Jieli toys

|             |                                                                                                                                                                                            |
|-------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Wi‑Fi name  | `HF_xxxxxxxx`                                                                                                                                                                              |
| Camera      | Jieli AC79 (`AC79X_wifi_car_camera`); vendor app *DVRunning 2* (also *HF UFO*)                                                                                                             |
| Toy address | `192.168.80.1`: control (CTP) on TCP port 3333; the video goes to UDP port 2224 (or 6666) **of the phone**                                                                                 |
| Slot URL    | `jieli://192.168.80.1` (or `jieli://` alone)                                                                                                                                               |
| Status      | **test build 3**, not in the releases: the control part follows a capture of the vendor app, the video part is **unverified**; issue [#187](https://github.com/OpenIPC/decoder/issues/187) |

What the app does, as a capture of the vendor app shows it ([#196](https://github.com/OpenIPC/decoder/issues/196)):

- It connects to TCP port 3333 and talks **CTP**: each message is `CTP:`, the length of a topic name, the topic (e.g. `OPEN_RT_STREAM`), the length of a JSON text and the JSON.
- `APP_ACCESS` first; the toy answers with `KEEP_ALIVE_INTERVAL` (a timeout, 60 s), its battery and SD card state and `VIDEO_PARAM` (the size, e.g. 640×480 at 25 fps). Then `DATE_TIME` (the phone's time), a `CTP_KEEP_ALIVE` every 7.5 s (a quarter of a shorter timeout), and `OPEN_RT_STREAM` with the size from `VIDEO_PARAM` (640×480 at 25 if the toy sent none). When the slot stops: `CLOSE_RT_STREAM`.
- The video was **not** in the capture (PCAPdroid without root cannot see it). After turbodrone's research on the same Jieli SDK it is **RTP/JPEG** (payload type 26) or H.264 (96) to the phone's **UDP port 6666**. The app listens there and plays payload type 26 through its RTP/JPEG path and 96 through its H.264 path; a whole JPEG in a datagram without RTP is shown too.
- A YL3 sent nothing to 6666 but its own stream to the phone's **UDP port 2224**: per datagram a 20-byte header, then the JPEG (seen: byte 0 = 2, a packet counter in bytes 4–7, the JPEG's length in 8–11, an offset in 12–15, little-endian; frames of 4–8 KB in one datagram, 40–60 zero bytes after the JPEG). Its frames are portrait (240×320 when 640×480 was asked for): the camera is mounted sideways, so `jieli://` turns them 270° counter-clockwise like the K200's, `?rotate=` overrides it. The app listens on 2224 as well and shows those JPEGs; longer frames are put together by the offset. The first five datagrams go to the log with the whole header, `/stats.json` has a `stream` object (`packets`, `frames`, `dropped`, `last_drop`) and `video_port`; why a frame was dropped is in the log for the first three. The debug page serves the last frame as it came, `/jieli-frame.jpg`, and the last dropped one, `/jieli-dropped.bin`.
- If nothing arrives on 6666 or 2224 for 5 s after `OPEN_RT_STREAM`, the status says so and the app also listens on UDP 1234 (audio in the vendor SDK), **only to log** what arrives there; it asks for the stream again every 10 s without video.
- **No flight command is ever sent**: no `FLIGHT_CONTROL` over CTP, nothing to the toy's UDP port 3456 (where the vendor app sends the sticks).
- Ports 6666 and 2224 can be used by one slot at a time: one `jieli://` slot in a grid.

URL options (as with `udp://`, separated by `?` and `&`):

| Option          | Values                  | Default                               | What it does                                                                                |
|-----------------|-------------------------|---------------------------------------|---------------------------------------------------------------------------------------------|
| `w`, `h`, `fps` | numbers                 | from `VIDEO_PARAM`, else 640, 480, 25 | the stream asked for in `OPEN_RT_STREAM`                                                    |
| `rtp`           | a UDP port              | `6666`                                | the local port the video is expected on                                                     |
| `format`        | `0`, `1`                | `0`                                   | `0` asks for JPEG (as the vendor app does), `1` for H.264                                   |
| `rotate`        | `0`, `90`, `180`, `270` | `270`                                 | degrees counter-clockwise the JPEG frames are turned (the YL3's camera is mounted sideways) |

Examples: `jieli://192.168.80.1`, `jieli://192.168.80.1?w=1280&h=720`, `jieli://192.168.80.1?format=1`.

Status texts while it connects: *Connecting to the toy (CTP 192.168.80.1:3333)*, *Stream requested, waiting for video on UDP 6666 and 2224*, *No video on UDP 6666 and 2224 yet: the toy accepted the request; see /log*, *Receiving from the toy on UDP 2224…* (the port the video came on), *The toy's video stopped: waiting on UDP 6666 and 2224*. The test build adds a `jieli` object to `/stats.json`: `state` (`connecting`, `ctp`, `requested`, `playing`, `quiet`), `target`, `network`, `ctp` (messages sent and received, keepalives and replies, the keepalive interval, the topics seen, the last 8 messages from the toy), `video_param`, `requested`, `rtp` (port, packets, video packets, frames, payload types, packets that are not RTP, the first packet in hex), `sources` (sender address:port → packets) and `diagnostic_ports`. The log has the CTP messages (JSON shortened) and the first packets on each port with sender, payload type and first bytes: exactly what a report needs.

To test: install the test build, join the `HF_…` Wi‑Fi, [stay connected](#connecting), close the vendor app, put `jieli://192.168.80.1` into a slot. Wait about 30 s and [send a report](#what-to-send-in-a-report) in [#187](https://github.com/OpenIPC/decoder/issues/187), with or without a picture: `/stats.json` and `/log` tell what the toy really sends.

## Credits

What the app does for FLOW-UFO and KY UFO toys was checked against [turbodrone](https://github.com/marshallrichards/turbodrone) (Apache-2.0), which flies these drones from a computer: its `wifi_uav` backend is the FLOW-UFO protocol and its `cooingdv` backend the KY UFO RTSP toys. The default quality parameters, the second port 8801 and its timing, the camera status bytes, leaving lagging frames out of the acknowledgement, the raster tables of `dqt=raster` and the 7099 heartbeat come from its code and research notes; no code of it is used. For the HF / Jieli toys its research notes on the same Jieli SDK (`rxdrone`, `cooingdv_jieli`: RTP/JPEG to UDP 6666, audio on 1234, the stream ports) describe the video part; the control part follows a PCAPdroid capture of the vendor app posted in [#196](https://github.com/OpenIPC/decoder/issues/196). More open-source work on such toys is collected in [#196](https://github.com/OpenIPC/decoder/issues/196).

## An unknown toy

First find the toy's address: on Android it is usually the gateway of the Wi‑Fi (*Wi‑Fi → the network → Details*; often `192.168.0.1`, `192.168.1.1`, `192.168.4.1`, `192.168.169.1`, `172.16.10.1` or `192.168.25.1`). Then try, in a slot:

| Try                                          | Why                                                                                     |
|----------------------------------------------|-----------------------------------------------------------------------------------------|
| `rtsp://<toy>:554/`                          | some toys run a plain RTSP server; try the paths `/live`, `/stream`, `/11`, `/h264` too |
| `rtsp://<toy>:8554/`                         | another common RTSP port                                                                |
| `http://<toy>:8080/?action=stream`           | MJPEG streamers (mjpg-streamer style)                                                   |
| `http://<toy>/` and `http://<toy>:81/stream` | MJPEG on the web port; open them in a browser first to see what answers                 |
| `udp://@:<port>`                             | only if the toy pushes RTP by itself to a port of the phone, which is rare              |

If nothing plays, the toy most likely uses a protocol of its own, which needs a [report](#what-to-send-in-a-report) and a transport written for it.

## What to send in a report

Open an [issue](https://github.com/OpenIPC/decoder/issues) (one per toy model) with:

1. The **Wi‑Fi name** of the toy (the part before the varying digits is enough) and the toy's make and model, with a photo of the box or label if possible.
2. The **vendor app**: its name, version and where it comes from (Google Play link).
3. The phone model and Android version, the app version and hash (menu, under **Exit**).
4. What you tried and what the app showed.
5. A **report file**, saved on the phone after about 30 s of trying: a long press on **Debug** in the menu writes `Download/OpenIPC/report-….txt` ([Save report](debug.md#save-report)). The toy's Wi‑Fi has no internet: save it there, leave the toy's network, then share it from the share sheet or attach the file from `Download/OpenIPC` to the issue. Without it, `http://127.0.0.1:8085/stats.json` and `http://127.0.0.1:8085/log`.
6. A **traffic capture** while the vendor app shows video: for example with [PCAPdroid](https://github.com/emanuele-f/PCAPdroid) on the phone (no root needed), filtered to the vendor app, saved as a `.pcap` file. A minute is enough. This shows what the toy expects and sends.

Do not send the vendor app itself or parts of it. The capture contains only the traffic between the phone and the toy; it has no passwords of yours if only the vendor app is captured.

## Tested toys

The drones a tester checked in [#187](https://github.com/OpenIPC/decoder/issues/187):

| Toy       | Wi‑Fi       | Family                      | URL                                 | Status                                                                                          |
|-----------|-------------|-----------------------------|-------------------------------------|-------------------------------------------------------------------------------------------------|
| D16       | `FLOW_…`    | FLOW-UFO, WIFI UAV app      | `flowufo://192.168.169.1:8800`      | video works                                                                                     |
| K200      | `FLY-UFO-…` | KY UFO, RTSP                | `rtsp://192.168.1.1:7070/webcam`    | video works; the picture comes sideways ([#200](https://github.com/OpenIPC/decoder/issues/200)) |
| Drone YL3 | `HF_…`      | Jieli AC79, DVRunning 2 app | `jieli://192.168.80.1` (test build) | in testing                                                                                      |

