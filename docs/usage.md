# Usage

- [First camera](#first-camera)
- [Menu](#menu)
- [Gestures and keys](#gestures-and-keys)
- [Remote control](#remote-control)
- [Multi-view](#multi-view)
- [Slides](#slides)
- [The U cell](#the-u-cell)
- [WebUI](#webui)
- [Opening a camera from another app](#opening-a-camera-from-another-app)
- [Screenshots](#screenshots)
- [Reset](#reset)

## First camera

1. Open the menu: tap the screen, or press **OK** on a remote.
2. Choose a slot (**1**–**8**), then **Settings**.
3. Enter the camera URL, for example `rtsp://user:password@192.168.1.10:554/stream=0`, and choose the transport under it (**TCP / UDP / Auto / Multicast**, a new slot starts on Auto). URL formats: [transports.md](transports.md).
   Below them, **Turn** (**Auto**, **0°**, **90° ↺**, **180°**, **270° ↺**, counter-clockwise) and **Mirror** set the picture for a camera mounted sideways or upside down, or seen in a mirror ([#201](https://github.com/OpenIPC/decoder/issues/201)). **Auto** leaves it to the URL's `?rotate=` and the toys' defaults. They apply in the single view and in the grid, to H.264, H.265 and MJPEG; the decoder turns the picture (Android 6+), and the mirror needs Android 7+.
4. **Save**. The app connects at once and reconnects by itself when the stream drops (exponential back-off, 1 s up to 30 s).

The URL dialog is titled `Camera N URL`. The picture is fitted to the screen whatever its shape, without cropping. URLs are stored encrypted, see [security.md](security.md#credential-store).

## Menu

The menu is one grid: the eight slots on top, two rows of four below. Every word cell is two slots wide.

```
[ 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 ]
[ Settings | WebUI  | Grid   | Slides ]
[ Exit     | Reset  | Debug  | UDP    ]
```

<p align="center">
  <img src="../screenshots/phone_menu.jpg" alt="The menu on a phone" width="400">
</p>

| Item         | Action                                                                                                                                                                                                                                                   |
|--------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **1**–**8**  | Switch to a camera slot (single view). The number of the active slot shows the connection quality: green under 100 ms of jitter, yellow 100–300 ms, red above. A grey number is an empty slot                                                            |
| **Settings** | URL, transport, turn and mirror of the active slot. Single view only, grey elsewhere                                                                                                                                                                     |
| **WebUI**    | The camera's web interface, see [WebUI](#webui). Single view only, grey elsewhere                                                                                                                                                                        |
| **Grid**     | The view: [multi-view](#multi-view) (2×2 or 3×3) or the single camera. Does not change the playback mode                                                                                                                                                 |
| **Slides**   | The playback mode: every frame, or key frames only, see [Slides](#slides). Does not change the view                                                                                                                                                      |
| **Exit**     | Close the app. The small text under it is the version and build hash (`v2.10, <hash>`)                                                                                                                                                                   |
| **Reset**    | Clear all settings after a confirmation, see [Reset](#reset)                                                                                                                                                                                             |
| **Debug**    | Text in the corner of every multi-view cell (resolution, codec, decoded/received fps, key frames, status), or none. **Long press** (remote: hold **OK**, or **Menu** on Debug): [save a report](debug.md#save-report) to `Download/OpenIPC` and share it |
| **UDP**      | [The U cell](#the-u-cell) full-screen                                                                                                                                                                                                                    |

Items that are on (Grid, Slides, Debug, UDP and the active slot) have an orange fill; the item the remote is on has a light blue frame. On a narrow screen or with a large font the labels are shortened with an ellipsis rather than clipped.

## Gestures and keys

| Input                                   | Action                                                                |
|-----------------------------------------|-----------------------------------------------------------------------|
| Tap / **OK** / **Enter** (D-pad centre) | Open or close the menu                                                |
| **Back** / **Esc**                      | Close the menu or a dialog; without one, Back keeps its system action |
| Pinch, then drag                        | Zoom (1× to 5×) and pan, single view                                  |
| Double tap                              | Reset the zoom                                                        |
| Long press, single view                 | Save a screenshot, see [Screenshots](#screenshots)                    |
| Long press, multi-view                  | Play this cell's sound; again to mute                                 |

## Remote control

The app is in the Leanback launcher of Android TV devices and does not require a touchscreen. **OK** opens the menu; the D-pad moves through the grid; the first key after a menu opened by touch moves the focus to the active item. In the reset dialog **Cancel** has the focus, so a stray **OK** deletes nothing.

## Multi-view

**Grid** shows the configured slots in a 2×2 grid, or in a 3×3 grid once any of slots 5–8 is used. The ninth cell of the 3×3 grid is [the U cell](#the-u-cell). Tap any cell to open the menu.

<p align="center">
  <img src="../screenshots/quad_menu.jpg" alt="Multi-view with eight cameras and the menu" width="640">
</p>

Each cell has its own connection and decoder. The device's video decoder has a fixed budget (the AG-216 test box decodes about two 1080p30 streams at full rate), so the grid adapts:

- Cells start on key frames only, then decode every frame in priority order while the decoder keeps up: the U cell while it receives a stream, the cell whose sound plays, then slot order.
- Cells beyond that stay on key frames: the picture updates once per key frame interval (`key frames` in the corner text). MJPEG cells show one frame per second instead (`reduced`).
- The limit is learnt per device by measurement and kept across restarts. How: [architecture.md](architecture.md#decode-planner).
- A key-frame cell beyond the hardware decoder's instance limit (the AG-216 has 4 × H.265) uses a software decoder (`· software` in the corner). Only beyond that does it show `Decoder limit: N × H.265`.
- When even key frames are too much, the lowest-priority cell is paused (`Paused: decoder overloaded`) and tried again after a quiet period. Losses in the first 10 s after the grid starts do not count.
- A cell whose UDP stream loses a piece of every key frame shows the frames with the damage concealed (`· damaged`) rather than a black picture.
- When frames arrive in several cells for 20 s and none is decoded, the app says that the device's video decoder does not respond (seen on the AG-216, [#145](https://github.com/OpenIPC/decoder/issues/145)); only a restart of the device helps.

Substreams of the cameras, where available, let more cells play at full rate. Some cameras accept only one RTSP client at a time.

**Audio:** long-press a cell to play its sound, long-press it again to mute. The selected cell shows an **AUDIO** badge and is remembered.

## Slides

**Slides** decodes key frames only, in multi-view and in the single view: the lowest decoder load, with the picture updated once per key frame interval (MJPEG once a second). It never changes the view, and **Grid** never changes Slides; both may be on. `/stats.json` reports the mode as `"mode": "slides"` in multi-view and `"decode": "key frames"`.

## The U cell

The U cell plays a camera that pushes RTP over UDP to port **5600** of the device (`udp://@:5600`), for example OpenIPC Majestic with an `outgoing` destination, see [transports.md](transports.md#udp-push-majestic-outgoing). Until a stream arrives it shows every address of the device to send to. **UDP** in the menu opens it full-screen. Other ports or multicast groups go into a slot as a `udp://` URL.

## WebUI

**WebUI** opens the camera's web interface in a built-in browser, with the credentials taken from the slot's RTSP URL. It uses the system WebView: it needs a working WebView package (on some devices Google's *Android System WebView*); without it only WebUI is affected ([#15](https://github.com/OpenIPC/decoder/issues/15)). On a touch device the page scrolls the focused field above the on-screen keyboard. **Close** closes it.

<p align="center">
  <img src="../screenshots/phone_webui.jpg" alt="WebUI on a phone: the page scrolls the field above the keyboard" width="400">
</p>

## Opening a camera from another app

The app accepts plain-text shares (`ACTION_SEND`, `text/plain`). Sharing an `rtsp://`, `udp://` or `http://` URL from another app, for example OpenIPC Network, puts it into the **active** slot, saves it and switches to the single view. An optional `transport` extra (`tcp`, `udp`, `auto`, `multicast`) sets the slot's transport; without it the slot keeps its setting.

```sh
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT 'rtsp://user:pass@192.168.1.10/stream=0' \
  --es transport udp -n com.openipc.decoder/.Decoder
```

## Screenshots

A long press in the single view saves the picture to `Pictures/OpenIPC` (at most 3840×2160; a larger stream is scaled down). Screenshots need Android 7.0 or newer; Android 7–9 ask for the storage permission first. For a picture of the whole grid from another computer use `/snapshot.jpg` of the [debug page](debug.md#snapshot).

## Reset

**Reset** asks *Start with a clean slate?*; **Clear settings** removes all camera slots, their transports and the other settings, and the app restarts as right after installing. The cameras are not changed. **Cancel** has the focus.
