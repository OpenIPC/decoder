<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://openipc.org/assets/openipc-logo-white.svg">
  <source media="(prefers-color-scheme: light)" srcset="https://openipc.org/assets/openipc-logo-black.svg">
  <img alt="OpenIPC logo" src="https://openipc.org/assets/openipc-logo-black.svg">
</picture>

## OpenIPC Decoder

Android application for viewing RTSP streams from IP cameras.

### Features

#### Core
- RTSP stream playback from IP cameras
- Support for H.264 and H.265 (HEVC) codecs
- Hardware decoding via MediaCodec
- Audio support (PCM, AAC, G.711)
- Multiple display modes:
  - Single camera
  - Carousel (automatic switching between cameras)
  - Quad mode (4 cameras simultaneously)

#### Controls
- Pinch-to-zoom gesture
- Pan by dragging
- Double-tap to reset zoom
- Settings menu with touch control
- Android TV (Leanback) support

#### Network capabilities
- TCP/UDP connectivity
- Automatic reconnection with exponential backoff
- Basic and Digest authentication support
- Built-in WebUI for camera interface access
- Connection status and quality indicators

#### Additional features
- Screenshot capture of current video
- Memory optimization with object pooling
- Real-time status indicators
- Carousel interval configuration
- Support for up to 8 cameras with individual settings

### Requirements
- Android 5.0 (API 21) or higher
- Network connection
- IP camera with RTSP support

### Quick Start

1. Install the application on your Android device
2. Launch the application
3. Tap the screen to open the menu
4. Configure camera URL in the format:
   ```
   rtsp://username:password@ip_address:port/stream
   ```
5. Select transport (TCP/UDP)
6. Tap camera number to activate

### Settings

#### Camera Menu
- **1-8** - camera slot selection
- **Settings** - advanced settings
- **Transport** - toggle between TCP/UDP
- **Carousel** - enable/disable carousel mode
- **Quad** - enable/disable quad mode
- **WebUI** - open camera web interface
- **Screenshot** - take screenshot
- **Exit** - exit application

#### Carousel Mode
- Automatic switching between selected cameras
- Configurable interval (3-120 seconds)
- Active camera indicator

#### Quad Mode
- Simultaneous viewing of 4 cameras
- Individual settings for each camera
- Optimized resource usage

### Technical Details

#### Architecture
- Multi-threaded processing (network, decoding, audio)
- Object pool to minimize GC pressure
- Separation of responsibilities between components

#### Security
- Credential removal from URLs in RTSP requests
- Basic and Digest authentication support
- Resource cleanup on pause/stop

#### Performance
- Hardware video decoding
- Audio buffering for smooth playback
- Memory usage optimization

### Building

```bash
./gradlew assembleRelease
```

#### Signing
To sign the release build, set environment variables:
```bash
export KEYSTORE_PASS=your_password
export KEY_ALIAS=your_alias
export KEY_PASS=your_key_password
```

### License
MIT License. See LICENSE file for details.

### Support
- Documentation: https://openipc.org
- Source code: https://github.com/openipc/decoder
- Questions and suggestions: via GitHub Issues

### Version History

#### 1.21 (Current)
- Improved reconnection with exponential backoff
- Added AAC audio support
- Memory optimization with object pooling
- Removed trailing whitespace
- Updated documentation

#### 1.20
- Added status and quality indicators
- Screenshot functionality
- User interface improvements

#### 1.19
- Resource leak fixes
- Improved error handling
- Network stability

#### 1.0-1.18
- Basic playback functionality
- H.264/H.265 support
- Carousel and quad modes
- Gesture controls
- Android TV support

### Statistics
**Please send information about devices where the program was tested only in this format:**
```
Device type, Manufacturer and Model, Android Version, Kernel Version
```

### Verified Devices
- Phones
    - Asus ZC553KL, Android 8.1, Kernel 3.18.71
    - Blackview BV4900Pro, Android 12, Kernel 4.19.191
    - Oppo 5X, ColorOS 15.0, Kernel 5.15.149
    - Oppo A17, ColorOS 12.1, Kernel 4.19.191
    - Samsung Galaxy A51, Android 13, Kernel 4.14.113
    - Samsung Galaxy M21 (SM-M215F/DSN), Android 12, Kernel 4.14.113
    - Samsung Galaxy S25, Android 15, Kernel 6.6.30
    - Samsung A55 5G, Android 14, Kernel 6.1.93
    - Samsung Galaxy J7, Android 10 , Kernel 3.18.150
    - Samsung S8, Android 9, Kernel 4.4.153
    - Samsung S23 Ultra, Android 15, Kernel 5.15.153
    - Xiaomi Redmi 7A, Android 10, Kernel 4.9.261
    - Xiaomi Redmi Note 7 (M1901F7G), Android 10, Kernel 4.4.192
    - Xiaomi Redmi Note 13 4G, Android 13, Kernel 5.15.94
- Tablets
    - Lenovo TB-X304L, Android 8.1.0, Kernel 3.18.71 (image cropped at edges)
    - Lenovo TB-X606F, Android 10, Kernel unknown (image cropped at edges)
    - Lenovo Tab P11, Android 11, Kernel 4.19.157-perf+ (image cropped at edges)
    - Samsung Galaxy Tab A, Android 7.1.1, Kernel 3.10.49
    - Samsung Galaxy Tab A, Android 11, Kernel 4.9.227
    - Samsung Tab A7 (SM-T505), Android 12, Kernel 4.19.157-perf-
- TVs and Set-top Boxes
    - A95X F3 AIR, Android 9, Kernel 4.9.113, SlimBOXtv AOSP 9.14 without GAPPS
    - IE X3 Air, Android 9, Kernel 4.9.113, SlimBOXtv AOSP 9.14 without GAPPS
    - Haier Candy Android TV 2K, Android 11, Kernel 4.9.243+ (no icon in device apps)
    - SberBox SBDV-00001, Custom ?, Kernel 4.9.228
    - TV Box X96Q, Android 10, Kernel 4.9.170 (original H313 SoC)
    - Yandex TV Novex NVX-55U169TSY, Android 7.1.1, Kernel 4.4.3
- Car Devices
    - Media Center JCAC10003, Android 12, Kernel 3.18.79+ (SoC ac8227/ac8229)
    - Mirror Z55, Android 8.1.0, Kernel 4.4.83

### Incompatible Devices
- Untested
    - TV box, Android 5.1.1, Kernel 3.14.29 (no video)

### Issues and Explanations

It has been noticed that the Decoder application may sometimes use some GAPPS components/libraries, so do not disable them or do it consciously, research in this area is highly encouraged!

[![Telegram](https://openipc.org/images/telegram_button.svg)][telegram]

[price]: https://openipc.org/support-open-source
[firmware]: https://github.com/openipc/firmware
[logo]: https://openipc.org/assets/openipc-logo-black.svg
[mit]: https://opensource.org/license/mit
[opencollective]: https://opencollective.com/openipc
[paypal]: https://www.paypal.com/donate/?hosted_button_id=C6F7UJLA58MBS
[project]: https://github.com/openipc
[telegram]: https://openipc.org/our-channels
[website]: https://openipc.org
[wiki]: https://github.com/openipc/wiki
