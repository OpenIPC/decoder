# Devices

- [Reporting](#reporting)
- [Verified devices](#verified-devices)
- [Not working](#not-working)
- [Hardware decoder notes](#hardware-decoder-notes)

## Reporting

Tested the app on your device? Please [**submit a Device report**](https://github.com/OpenIPC/decoder/issues/new?template=device_report.yml). It takes a minute and helps other users. Tested a camera? [**Submit a Camera report**](https://github.com/OpenIPC/decoder/issues/new?template=camera_report.yml): manufacturer, model, RTSP URL (without the password), codecs and what works. Reports are collected into the tables below. A `/stats.json` from the [debug page](debug.md) lists the device's decoders and their limits (`decoders`), which helps most with decoder problems.

## Verified devices

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
| Phone       | TECNO KM4                              | 15                                | 5.15.194       | v2 2.04 | Lab touch device: WebUI with the on-screen keyboard checked ([#159](https://github.com/OpenIPC/decoder/issues/159), [#160](https://github.com/OpenIPC/decoder/issues/160)); Close under the status bar ([#169](https://github.com/OpenIPC/decoder/issues/169)) |
| Phone       | Xiaomi Redmi 7A                        | 10                                | 4.9.261        | 1.0x    |                                                                                            |
| Phone       | Xiaomi Redmi Note 7 (M1901F7G)         | 10                                | 4.4.192        | 1.0x    |                                                                                            |
| Phone       | Xiaomi Redmi Note 13 4G                | 13                                | 5.15.94        | 1.0x    |                                                                                            |
| Tablet      | Lenovo TB-8504X                        | 8.1.0                             | 3.18.71        | v2 2.00 | H.265 and multi-view work; WebUI input fixed in 2.02 ([#159](https://github.com/OpenIPC/decoder/issues/159)) |
| Tablet      | Lenovo TB-X304L                        | 8.1.0                             | 3.18.71        | 1.0x    | Picture cropped in 1.0x, fixed in v2 ([#17](https://github.com/OpenIPC/decoder/issues/17)) |
| Tablet      | Lenovo TB-X606F                        | 10                                | —              | 1.0x    | Picture cropped in 1.0x, fixed in v2 ([#17](https://github.com/OpenIPC/decoder/issues/17)) |
| Tablet      | Lenovo Tab P11                         | 11                                | 4.19.157-perf+ | 1.0x    | Picture cropped in 1.0x, fixed in v2 ([#17](https://github.com/OpenIPC/decoder/issues/17)) |
| Tablet      | Samsung Galaxy Tab A                   | 7.1.1                             | 3.10.49        | 1.0x    |                                                                                            |
| Tablet      | Samsung Galaxy Tab A                   | 11                                | 4.9.227        | 1.0x    |                                                                                            |
| Tablet      | Samsung Tab A7 (SM-T505)               | 12                                | 4.19.157-perf- | 1.0x    |                                                                                            |
| Set-top box | A95X F3 AIR                            | 9 (SlimBOXtv AOSP 9.14, no GAPPS) | 4.9.113        | 1.0x    |                                                                                            |
| Set-top box | IE X3 Air                              | 9 (SlimBOXtv AOSP 9.14, no GAPPS) | 4.9.113        | 1.0x    |                                                                                            |
| Set-top box | AG-216                                 | 10                                | 4.9.170        | v2 2.00 | v2 test device; decoder budget measured at ≈ 1080p60 in total (two 1080p30 at full rate)   |
| Set-top box | SberBox SBDV-00001                     | custom                            | 4.9.228        | 1.0x    |                                                                                            |
| Set-top box | X96Q (Allwinner H313)                  | 10                                | 4.9.170        | 1.0x    |                                                                                            |
| Projector   | SIBIRLINK HY300 / HY320                | 11                                | 4.19.323       | v2 2.02 | 1080p H.264 / H.265 work; 2304×1296 and 2560×1440 do not start ([#165](https://github.com/OpenIPC/decoder/issues/165), [#166](https://github.com/OpenIPC/decoder/issues/166)) |
| TV          | Haier Candy Android TV 2K              | 11                                | 4.9.243+       | 1.0x    | No icon in the device's app list                                                           |
| TV          | Yandex TV Novex NVX-55U169TSY          | 7.1.1                             | 4.4.3          | 1.0x    |                                                                                            |
| Car         | Media Center JCAC10003 (ac8227/ac8229) | 12                                | 3.18.79+       | 1.0x    |                                                                                            |
| Car         | Mirror Z55                             | 8.1.0                             | 4.4.83         | 1.0x    |                                                                                            |

## Not working

| Type        | Device         | Android | Kernel  | App  | Notes    |
|-------------|----------------|---------|---------|------|----------|
| Set-top box | Unnamed TV box | 5.1.1   | 3.14.29 | 1.0x | No video |

## Hardware decoder notes

What the app has measured or met on real devices. The decoders of a device are listed in `/stats.json` (`decoders`: `name`, `type`, `hardware`, `adaptive`, `max_instances`, `fps_1080p`; see [debug.md](debug.md#decoders)).

| Topic | Note |
|---|---|
| Decoder budget | The AG-216 (Allwinner H313/H616 family, Android 10) decodes about 1080p60 in total: two 1080p30 streams at full rate. Multi-view learns the budget of each device by measurement, see [architecture.md](architecture.md#decode-planner) |
| Instance limit | The AG-216 allows 4 hardware H.265 decoder instances. Key-frame cells beyond that use a software decoder (`· software`); beyond that too, `Decoder limit: N × H.265` |
| Stuck decoder | The AG-216's video decoder can stop responding until the device is restarted: frames arrive, none is decoded. The app says so on screen and in `/stats.json` (`decoder_stuck`). Cause unknown ([#145](https://github.com/OpenIPC/decoder/issues/145)) |
| MJPEG | Hardware JPEG decoding is used where the device offers `video/jpeg` (also `video/mjpeg`, `video/x-motion-jpeg`). The AG-216 declares an Allwinner MJPEG decoder that Android does not list and that cannot be created; MJPEG is then decoded with BitmapFactory ([#132](https://github.com/OpenIPC/decoder/issues/132)) |
| Window buffer stalls | On Mali TV boxes the app's window buffer stalled for seconds with every grid/single switch while the single view used a `TextureView`; since 2.09 the single view is a `SurfaceView` ([#76](https://github.com/OpenIPC/decoder/issues/76)) |
| Large resolutions | The SIBIRLINK HY300 / HY320 projector plays 1080p H.264 / H.265, but 2304×1296 and 2560×1440 do not start ([#165](https://github.com/OpenIPC/decoder/issues/165), [#166](https://github.com/OpenIPC/decoder/issues/166)) |
| Old Android | Android 5.0 is the minimum. Encrypted URL storage needs 6.0, multicast 8.0, screenshots and snapshots 7.0 |
