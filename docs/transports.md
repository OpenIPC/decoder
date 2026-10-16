# Transports

- [Overview](#overview)
- [RTSP](#rtsp)
- [RTSP transport modes](#rtsp-transport-modes)
- [UDP push (Majestic outgoing)](#udp-push-majestic-outgoing)
- [HTTP MJPEG](#http-mjpeg)
- [Toys: FLOW-UFO](#toys-flow-ufo)
- [Codecs](#codecs)
- [Authentication](#authentication)
- [What the servers support](#what-the-servers-support)

## Overview

A slot holds one URL. Its scheme picks the path the video takes; for `rtsp://` the slot's transport mode (chosen under the URL in **Settings**, or with the `transport` extra of a [shared link](usage.md#opening-a-camera-from-another-app)) picks how RTP travels.

| URL | Path | Single view | Multi-view | Audio |
|---|---|---|---|---|
| `rtsp://` | RTSP session, RTP over TCP, UDP or multicast | yes | yes | yes |
| `udp://`, `rtp://` | plain RTP pushed by the camera, no RTSP | yes | yes | no |
| `http://` | MJPEG, `multipart/x-mixed-replace` | yes | yes | no |
| `flowufo://` | toy drone protocol, **test build only** ([#187](https://github.com/OpenIPC/decoder/issues/187)) | yes | yes | no |

The transport in use is shown when a stream starts in the single view (`Connected • UDP • H.265 …`), on the [debug page](debug.md) and in `/stats.json` (`transport`; the slot's setting is `transport_mode`). After a fallback it reads, for example, `TCP (auto)`.

<p align="center">
  <img src="../screenshots/single_status.jpg" alt="Single view right after connecting: transport, codecs and bitrate" width="640">
</p>

## RTSP

```
rtsp://[user:password@]host[:port]/path
```

- Port 554 when none is given.
- `DESCRIBE`, `SETUP` (video, and audio when there is a supported audio track), `PLAY`; keepalive at half the session timeout the server announces.
- The video track is the first one offering H.264 or H.265 (also the legacy payload types 96/97 without `rtpmap`), otherwise MJPEG (RTP/JPEG, RFC 2435, static payload type 26). A session description with neither fails the connection, so the back-off applies instead of a black view.
- A server that omits `Content-Length` on the SDP is read until the line goes quiet (300 ms, at most 64 KiB).
- Connect and handshake time out after 5 s each. Without usable video packets for 8 s the watchdog reconnects. Reconnects back off exponentially from 1 s to 30 s.
- Packets over UDP are put back in order by a reorder buffer (the lab cameras reorder by up to ~42 positions). A NAL unit with a lost packet is dropped, see `nal_dropped` in [debug.md](debug.md#statsjson-fields).

## RTSP transport modes

| Mode | How the video arrives | Fallback |
|---|---|---|
| **TCP** | RTP interleaved in the RTSP connection | none needed; works through NAT and firewalls |
| **UDP** | RTP over UDP, a port pair per track | never falls back. Lost packets are skipped, not resent |
| **Auto** (default for a new slot) | UDP, else TCP | to TCP when the camera refuses UDP in `SETUP`, when no video arrives within 3 s, or when packets arrive but key frames keep losing a piece |
| **Multicast** | RTSP multicast (Android 8.0+) | to TCP in the same connection when the server refuses it or answers with unicast |

- After a fallback the URL is remembered as failed for that mode for 10 minutes while the app runs: reconnects, a resume or a rebuilt grid go straight to TCP; after that UDP (or multicast) is tried again.
- If the server answers a UDP request with interleaved TCP, the app takes what the server chose.
- An unknown stored or shared mode value means TCP.
- On Android 5–7 a Multicast slot plays over TCP.

## UDP push (Majestic outgoing)

A slot can receive RTP that the camera pushes by itself, without RTSP. Put a `udp://` (or `rtp://`) URL into the slot:

```
udp://[source@][address]:port[?codec=h264|h265][&pt=N]
```

| URL | Meaning |
|---|---|
| `udp://@:5600` | Listen on port 5600 |
| `udp://@239.1.2.3:5600` | Join the multicast group 239.1.2.3 on port 5600 |
| `udp://192.168.1.10@239.1.2.3:5600` | The same, but accept packets from this sender only |
| `udp://@:5600?codec=h265&pt=97` | Force the codec or the video payload type |

| Option | Values | Default |
|---|---|---|
| `codec` | `h264` / `avc`, `h265` / `hevc` | from the payload type: 96 → H.264, 97 → H.265 |
| `pt` | 0–127 | 96 or 97 |

- Addresses must be numeric (IPv4 or IPv6 in brackets); no name lookup. A port is required.
- A codec change on the camera is followed while playing. Video only.
- Two slots cannot listen on the same port. The [U cell](usage.md#the-u-cell) listens on 5600.
- On Wi‑Fi the app holds a multicast lock while it is in a group.
- A pushed stream cannot fall back to TCP: when every key frame loses a piece, the cell decodes the damaged frames (`· damaged`).

On the camera, add the device as an `outgoing` destination in `/etc/majestic.yaml`, then restart Majestic:

```yaml
outgoing:
  servers:
    - udp://192.168.1.20:5600
```

`/api/v1/outgoing.json` on the camera shows whether the destination is live. Majestic can also send to a multicast group (`udp://239.1.2.3:5600`), so several devices can watch one camera.

## HTTP MJPEG

```
http://[user:password@]host[:port]/path
```

For example Majestic's `http://user:password@<camera>/mjpeg`: a `multipart/x-mixed-replace` stream with one JPEG per part, chunked or not. Port 80 when none is given. Basic credentials are sent with the request, Digest after the camera asks for it. The stream reconnects like RTSP. It plays in the single view and in multi-view; the transport shows as `HTTP`. No audio on this path. `https://` is not supported.

## Toys: FLOW-UFO

`flowufo://192.168.169.1:8800` (or `flowufo://` alone) plays the video of toy drones with a `FLOW-UFO-xxxxxx` Wi‑Fi access point. It exists only in a test build so far ([#187](https://github.com/OpenIPC/decoder/issues/187), PR [#188](https://github.com/OpenIPC/decoder/pull/188)), not in the releases. Port 8800 (the default) probes 8801 as well and keeps the port that answers. Options: `?qpara=<8 hex digits>` and `?dqt=zigzag|raster|q50`. Setup, options and status: [toys.md](toys.md#flow-ufo).

`jieli://192.168.80.1` (or `jieli://` alone) plays toy drones with an `HF_xxxxxxxx` Wi‑Fi (Jieli AC79 camera): CTP commands on TCP 3333 ask for the stream, which arrives as JPEG frames behind a 20-byte header on the phone's UDP port 2224 (seen from a YL3) or as RTP/JPEG on UDP 6666 (test build only). Options: `?w=`, `?h=`, `?fps=`, `?rtp=<port>`, `?format=0|1`. See [toys.md](toys.md#hf--jieli-toys).

RTSP slots on `rtsp://<host>:7070/webcam` (KY UFO and other cooingdv toys) also send the toy's UDP heartbeat `01 01` to port 7099 once a second; `?heartbeat=<port>` turns it on for other RTSP URLs, `?heartbeat=off` off. Their MJPEG frames are drawn turned 270° counter-clockwise (90° clockwise) (the K200's camera is mounted sideways, [#200](https://github.com/OpenIPC/decoder/issues/200)); `?rotate=0|90|180|270` (degrees counter-clockwise, any RTSP MJPEG slot) overrides it and is not sent to the camera either. See [toys.md](toys.md#ky-ufo-rtsp-toys).

## Codecs

| Kind | Codecs | Decoding |
|---|---|---|
| Video | H.264, H.265 | MediaCodec (hardware); a software decoder for key-frame cells beyond the hardware instance limit |
| Video | MJPEG (RTP/JPEG over RTSP, HTTP MJPEG) | the device's JPEG decoder (`video/jpeg`) where it can be created, otherwise BitmapFactory |
| Audio | AAC (RFC 3640 `mpeg4-generic`, ADTS, MP4A-LATM with out-of-band config), Opus | MediaCodec |
| Audio | G.711 μ-law / A-law, L16 PCM (mono or stereo) | converted in the app |

Not supported: G.726, MP4A-LATM with in-band configuration (`cpresent=1`) ([#14](https://github.com/OpenIPC/decoder/issues/14)). An unsupported audio track is not set up; the video plays without sound.

## Authentication

- Credentials come from the URL (`user:password@`) and go in the `Authorization` header: Basic first, Digest when the camera asks for it. They are not put in the RTSP request line.
- Credentials that a camera expects in the URL path or query (for example XM's `user=…_password=…`) are sent as part of the URL with every request.
- Nothing is encrypted: see [security.md](security.md#network).

## What the servers support

Measured on the lab cameras; details and dates in [LABORATORY.md](../LABORATORY.md#what-the-servers-support).

| Feature | OpenIPC Majestic | XM (`H264DVR 1.0`) |
|---|---|---|
| RTP over TCP (interleaved) | yes | yes |
| RTP over UDP | yes | yes (reply without `/UDP`) |
| RTSP multicast | no: `400` to a multicast request, silent unicast when the request also has `client_port` | no: `451`, silent unicast likewise |
| Plain RTP push (`outgoing`), unicast or multicast | yes | not known |
| HTTP MJPEG | yes (`/mjpeg`) | not tested |
| Audio | Opus, AAC, L16 (G.711 not testable on the lab build) | none (video H.264 only) |

With a Multicast slot both play over TCP in the same connection. Multicast from OpenIPC is available only as plain RTP via `outgoing`. A server that does RTSP multicast (for example mediamtx) is needed to use the Multicast mode ([#69](https://github.com/OpenIPC/decoder/issues/69)).
