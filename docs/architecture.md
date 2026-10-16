# Architecture

- [Data path](#data-path)
- [Classes](#classes)
- [Threads](#threads)
- [Decode planner](#decode-planner)
- [Rendering: SurfaceView](#rendering-surfaceview)
- [Settings and state](#settings-and-state)
- [Repository layout](#repository-layout)

## Data path

```
 layer        rtsp://                        udp://  rtp://        http://
 ─────────────────────────────────────────────────────────────────────────────────
 network      RtspSession                    RtpListener           HttpMjpeg
              RtspAuth, Sdp, Transport,      pushed RTP,           multipart MJPEG,
              UdpReceiver, RtpReorder        video only            video only
                 │               │                │                     │
 assembly     NalAssembler    JpegAssembler   NalAssembler              │ whole JPEGs
              H.264/H.265     RTP/JPEG        H.264/H.265               │
                 │               │                │                     │
                 │               └────────────────┼──────────┐          │
                 ├────────────────────────────────┘          │          │
                 ▼                                           ▼          ▼
 queue        bounded frame queue per stream, buffers from FramePool
                 │                                           │
                 ▼                                           ▼
 decode       MediaCodecManager                         JpegRenderer
              MediaCodec H.264/H.265                    JpegCodec or BitmapFactory
                 │                                           │
                 ▼                                           ▼
 screen       SurfaceView: the single view (Decoder) or one per multi-view cell (QuadCell)

 audio        RtspSession → AudioConfig / AudioManager → MediaCodec (AAC, Opus)
              or conversion (G.711, L16) → AudioTrack; one stream's sound at a time
 beside       DecodePlanner (multi-view, once a second), DebugServer on port 8085
```

The single view runs one `RtspClient` (one `RtspSession`, assemblers, audio, connection quality) feeding the activity's decoder. Multi-view runs one `QuadCell` per cell, each with its own session, assemblers, queue and decoder. `udp://` and `http://` slots use the same `RtspSession` front, which hands their packets or frames to the same callbacks.

## Classes

| Class                                              | Role                                                                                                                                                             |
|----------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `Decoder`                                          | Main activity: UI, lifecycle, settings, single-view decoding, multi-view grid, debug state                                                                       |
| `RtspClient`                                       | Single-view stream: RTSP session, NAL assembly, audio, connection quality (jitter)                                                                               |
| `RtspSession`                                      | One session over TCP, UDP or multicast (and the `udp://`, `http://` fronts): handshake, RTP receive, keepalive, watchdog, reconnect with back-off, Auto fallback |
| `RtspAuth`, `Sdp`                                  | Basic and Digest authentication; parsing of the session description                                                                                              |
| `Transport`                                        | Slot transport mode; the RTSP `Transport` header (request and reply)                                                                                             |
| `UdpReceiver`, `RtpReorder`                        | UDP and multicast receive with a sender filter; packet reordering                                                                                                |
| `RtpListener`                                      | `udp://` slots: plain RTP pushed by the camera                                                                                                                   |
| `HttpMjpeg`                                        | `http://` slots: MJPEG over HTTP (`multipart/x-mixed-replace`, chunked or not)                                                                                   |
| `NalAssembler`                                     | H.264/H.265 NAL units from RTP; drops units damaged by packet loss, or passes them on cut short                                                                  |
| `JpegAssembler`                                    | Whole JPEG frames from RTP/JPEG (RFC 2435)                                                                                                                       |
| `JpegCodec`, `JpegRenderer`                        | MJPEG on screen: the device's JPEG decoder where there is one, otherwise BitmapFactory                                                                           |
| `MediaCodecManager`, `DecoderRetry`                | Video decoder lifecycle, instance budget per MIME type, software fallback, recovery after errors                                                                 |
| `CodecInfo`                                        | The device's video decoders and their declared limits (for `/stats.json`)                                                                                        |
| `DecodePlanner`                                    | Multi-view: which cells decode every frame and which only key frames                                                                                             |
| `AudioManager`, `AudioConfig`                      | Audio: G.711/L16 conversion, AAC/Opus decoding, AudioTrack playback                                                                                              |
| `QuadCell`                                         | One multi-view cell: its own session, decoder, queue and optional audio                                                                                          |
| `MenuManager`                                      | Menu grid, URL dialog, reset dialog, WebUI browser                                                                                                               |
| `CredentialStore`, `KeystoreCipher`                | Encrypted storage of camera URLs (Android Keystore)                                                                                                              |
| `FramePool`, `Frame`                               | Buffer pool that reduces garbage-collector load                                                                                                                  |
| `DebugServer`, `LogBuffer`, `NetInfo`, `Snapshots` | Debug server on port 8085, redacted log ring, device addresses and the LAN rule, screen snapshots                                                                |
| `StreamProbe`, `StreamProbes`                      | Short stream checks of the slots not on screen (single view, debug page in use)                                                                                  |
| `App`, `Crash`, `Restart`                          | Uncaught-exception handler and crash screen; restart after Reset                                                                                                 |

## Threads

Network, video decoding and audio run on separate threads, so a slow decoder never blocks the socket.

| Thread                 | Work                                                                        |
|------------------------|-----------------------------------------------------------------------------|
| UI (main)              | layout, menu, planner step and stats once a second, debug state on request  |
| `<tag>-net`            | one per session: connect, RTSP, receive RTP or HTTP, assemble, queue        |
| `<tag>-wd`             | one per session: watchdog (no video for 8 s → reconnect) and RTSP keepalive |
| single-view pool (2)   | video decode from the queue; audio playback                                 |
| `Quad-N-dec`           | one per multi-view cell: decode from the cell's queue                       |
| `quad-audio`           | audio of the selected multi-view cell                                       |
| `rtsp-aac-decode`      | AAC/Opus output of the audio decoder                                        |
| `decoder-release`      | short-lived: stops and releases a decoder off the UI thread                 |
| `debug-http-*`         | accept loop, two workers at background priority, request deadlines          |
| `probe-sched`, `probe` | stream checks: scheduler and two workers at background priority             |
| `codec-info`           | lists the device's decoders once at start                                   |

The queues between network and decoder are bounded (30 frames per cell); when a queue is full, the frame is dropped and counted as a decoder loss (`lost_fps`), not a network loss.

## Decode planner

`DecodePlanner` ([#85](https://github.com/OpenIPC/decoder/issues/85), [#189](https://github.com/OpenIPC/decoder/issues/189)) decides once a second which multi-view cells decode every frame and which only key frames, so that every camera stays on screen while the hardware decoder keeps up. Pure logic, unit-tested without Android.

**Load** is counted in macroblocks per second (16×16 blocks × frames per second), the unit decoders declare their throughput in. A cell's load is its full-rate load or its key-frame load; paused cells and cells on a software decoder do not count.

**Capacity** starts as the value learnt on this device before, or the decoder's declared budget, or 1080p60 when none is declared. It is never below one 1080p30 stream (the floor): losses below that come from a stalled system, not from the decoder.

**Priority:** the U cell while it receives a stream, the cell whose sound plays, then slot order.

| Step        | Rule                                                                                                                                                                                                                                                        |
|-------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| warm-up     | the first 10 s after the grid starts: losses decide nothing, promotions that fit go on                                                                                                                                                                      |
| settle      | after a change, 3 s before the next one                                                                                                                                                                                                                     |
| loss signal | decoder losses (queue full, no input buffer): at full rate ≥ 2 frames/s and ≥ 10 % of the received rate; on key frames any loss. 3 lossy samples within 10 s trigger a demotion                                                                             |
| demote      | the lowest-priority full-rate cell goes to key frames. The capacity drops to 90 % of the load, never below a proven load; that load is remembered as failed, and the next probe waits twice as long (up to 10 min)                                          |
| transient   | a loss below a load that already played quietly is forgiven once: the cell is demoted, but the capacity and the failed load are kept. Forgiven again only after a load at least as high plays quietly again                                                 |
| pause       | when even key frames are too much, the lowest-priority cell is paused (never the last one playing); it is resumed on key frames after a quiet period that doubles with every pause                                                                          |
| promote     | the highest-priority key-frame cell whose load is known, if the result fits within the capacity and stays below 90 % of a failed load. Beyond the capacity only after a quiet period (a probe, from 30 s). A lower cell may go first only if it surely fits |
| proven      | a load that plays 30 s without losses is proven; above the capacity it raises it. A failure at about that load is forgotten                                                                                                                                 |
| retry       | a failed load is tried again one hour after the last demotion                                                                                                                                                                                               |
| unknown     | a playing cell whose size and rate are not known yet holds the lower cells for up to 10 s                                                                                                                                                                   |
| stall       | a second in which nothing decodes at all (and nothing is lost) is skipped and does not count as quiet                                                                                                                                                       |

The learnt capacity and the failed load are stored per device (`decoder_capacity_v2`, `decoder_failed_load_v2`); stored values at or below the floor are not trusted. `/stats.json` shows them under `decoder` ([debug.md](debug.md#top-level)).

With **Slides** on, the planner is bypassed: every cell decodes key frames only.

## Rendering: SurfaceView

Both the single view and the multi-view cells render into `SurfaceView`s, not `TextureView`s.

- Multi-view ([#61](https://github.com/OpenIPC/decoder/issues/61)): with a `TextureView` every decoded frame raises a frame-available callback that the framework delivers by attaching a native thread to the VM, about 1 MB/s of garbage with four streams.
- Single view ([#76](https://github.com/OpenIPC/decoder/issues/76), [#185](https://github.com/OpenIPC/decoder/pull/185)): the decoder renders to a layer of its own instead of the app's window buffer, whose `queueBuffer` stalled for seconds with every grid/single switch on Mali TV boxes.

Consequences:

- Zoom and pan lay the view out at the zoomed size and move it (a `SurfaceView` has no `setTransform()`, and its surface follows `setScaleX()` only from Android 7).
- Screenshots and `/snapshot.jpg` use `PixelCopy` (no `getBitmap()`), so they need Android 7.0+.
- MJPEG draws with `lockCanvas()`; switching back to a video decoder needs a fresh surface, so the view is hidden for a moment.

## Settings and state

`SharedPreferences` (`settings`): per slot `host_N` (the URL, sealed by `CredentialStore`) and `transport_N` (`TCP`, `UDP`, `AUTO`, `MULTICAST`; a slot without a value is Auto); the active slot and the view and modes (`active`, `quad_enabled`, `slides`, `show_stats`, `quad_audio`); the learnt decoder capacity. **Reset** clears it and restarts the app through the `Restart` activity in a separate process. See [security.md](security.md#credential-store).

## Repository layout

```
android/                       Gradle project
  app/build.gradle             versionName / versionCode, release signing, R8
  app/lint-baseline.xml        known lint findings; only new ones fail CI
  app/src/main/java/com/openipc/decoder/   the app (one package)
  app/src/test/java/com/openipc/decoder/   JVM unit tests
.github/workflows/build.yml    CI: tests, lint, signed builds, releases
.github/ISSUE_TEMPLATE/        device and camera report forms
docs/                          these documents
screenshots/                   README pictures
```

The legacy 1.0x version lives in the `basic` branch (single file, built as `decoder.apk`).
