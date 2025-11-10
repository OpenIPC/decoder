<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://openipc.org/assets/openipc-logo-white.svg">
  <source media="(prefers-color-scheme: light)" srcset="https://openipc.org/assets/openipc-logo-black.svg">
  <img alt="OpenIPC logo" src="https://openipc.org/assets/openipc-logo-black.svg">
</picture>

## Decoder
**Miniature and universal H.264 and H.265 decoder for Android devices**

[![Telegram](https://openipc.org/images/telegram_button.svg)][telegram]

### Introduction
A very simple and lite application (~30 KB!) is designed to interact with devices that have OpenIPC firmware and can be used for the following purposes:
- Any testing and experiments with RTSP
- Phones and Tablets
- TVs and connected Boxes
- Car Players and Mirrors
- Intercom systems
- Baby Monitors
- Radio-controlled Toys

**The program is tested work on the Android OS from 8.0 (SDK/Level 26) to 15 (SDK/Level 35)**

To understand the SDK version, Android version and other relationships, please see this [table](https://apilevels.com/).

_During the testing phase, the application is distributed only in binary form, sorry._

![Menu](photo_menu.jpg)

### Version history
- [v1.07](https://play.google.com/apps/internaltest/4701231141824898735), 2025.11.11
- To receive or update this app, you need to provide your email through [OpenIPC](https://t.me/openipc) to be added to the Google Play testers list
    - Fixed the WebUI window on older devices
- [v1.06](https://play.google.com/apps/internaltest/4701231141824898735), 2025.11.10
    - Fixed a problem with a occasional mediacodec crash
    - The compass icon is gone now, a click on the screen will open the menu instead
    - There is a blue outline on the selected item
    - Exit button should remove the task now
    - Text selection now starts at the end of the link
- v1.05, 2025.09.28
    - Some fixes allowing the Decoder to work together with the OpenIPC Network application
- v1.04, 2025.09.24
    - Now a unified RTSP string containing all parameters is used (requires deleting previous versions of the program or settings)
    - First publication through closed tests on Google Play
    - Alpha version to support more devices, Android OS from 5 (SDK/Level 21) to 15 (SDK/Level 35)
    - The current size of the application is 25 kilobytes
- v1.03, 2025.09.08
    - Now we can specify the port next to the device address
    - Accepts external calls and can work together with OpenIPC Network
    - Some other fixes
    - The current size of the application is 23 kilobytes
- v1.02, 2025.09.03
    - Announcement and first public release
    - New compact and convenient menu
    - Audio (PCM, 8k) is now available when receiving RTSP over TCP/UDP
    - RTP UDP receiving is temporarily disabled
    - Experimental bluetooth button controller is temporarily disabled
    - The current size of the application is 23 kilobytes
- v1.01, 2025.09.01
    - First unofficial testing version where RTSP and other features were enabled
    - The current size of the application is 25 kilobytes

### Statistics
**Please send information about devices where the program was tested only in this format:**
```
Device type, Manufacturer and Model, Android Version, Kernel Version
```

### Verified devices
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
    - Lenovo TB-X304L, Android 8.1.0, Kernel 3.18.71 (the image is cut off at the edges)
    - Lenovo TB-X606F, Android 10, Kernel unknown (the image is cut off at the edges)
    - Lenovo Tab P11, Android 11, Kernel 4.19.157-perf+ (the image is cut off at the edges)
    - Samsung Galaxy Tab A, Android 7.1.1, Kernel 3.10.49
    - Samsung Galaxy Tab A, Android 11, Kernel 4.9.227
    - Samsung Tab A7 (SM-T505), Android 12, Kernel 4.19.157-perf-
- TV and Boxes
    - A95X F3 AIR, Android 9, Kernel 4.9.113, SlimBOXtv AOSP 9.14 without GAPPS
    - IE X3 Air, Android 9, Kernel 4.9.113, SlimBOXtv AOSP 9.14 without GAPPS
    - Haier Candy Android TV 2K, Android 11, Kernel 4.9.243+ (no icon in apps on device)
    - SberBox SBDV-00001, Custom ?, Kernel 4.9.228
    - TV Box X96Q, Android 10, Kernel 4.9.170 (original H313 SoC)
    - Yandex TV Novex NVX-55U169TSY, Android 7.1.1, Kernel 4.4.3
- Car Devices
    - Media Center JCAC10003, Android 12, Kernel 3.18.79+ (SoC ac8227/ac8229)
    - Mirror Z55, Android 8.1.0, Kernel 4.4.83

### Incompatible devices
- Untested
    - TV box, Android 5.1.1, Kernel 3.14.29 (no video)

### Problems and explanations

It has been noticed that the Decoder application may sometimes use some GAPPS components/libraries, so do not disable them or do it consciously, research in this area is highly encouraged !


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
