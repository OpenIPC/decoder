<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://openipc.org/assets/openipc-logo-white.svg">
  <source media="(prefers-color-scheme: light)" srcset="https://openipc.org/assets/openipc-logo-black.svg">
  <img alt="OpenIPC logo" src="https://openipc.org/assets/openipc-logo-black.svg">
</picture>

## Decoder
**Miniature and universal H.264 and H.265 decoder for Android devices**

[![Telegram](https://openipc.org/images/telegram_button.svg)][telegram]

### Introduction
A very simple application (25 KB!) is designed to interact with devices that have OpenIPC firmware and can be used for the following purposes:
- Any testing and experiments with RTSP
- Phones and Tablets
- TVs and connected Boxes
- Car Players and Mirrors
- Intercom systems
- Baby Monitors
- Radio-controlled Toys

**The program is guaranteed to basic work on the Android OS from 8.0 (SDK/Level 26) to 15 (SDK/Level 35)**

To understand the SDK version, Android version and other relationships, please see this [table](https://apilevels.com/).

_During the testing phase, the application is distributed only in binary form._


![Menu](photo_menu.jpg)

### Version history
- [v1.03.2, 2025.09.14](https://github.com/OpenIPC/decoder/releases/download/latest/decoder_v1.03.2_20250914.apk)
    - Alpha version for old devices 
- [v1.03, 2025.09.08](https://github.com/OpenIPC/decoder/releases/download/latest/decoder_v1.03_20250908.apk)
    - Now we can specify the port next to the device address
    - Accepts external calls and can work together with OpenIPC Network
    - Many other fixes
- [v1.02, 2025.09.03](https://github.com/OpenIPC/decoder/releases/download/latest/decoder_v1.02_20250903.apk)
    - Announcement and first public release
    - New compact and convenient menu
    - Audio (PCM, 8k) is now available when receiving RTSP over TCP/UDP
    - RTP UDP receiving is temporarily disabled
    - Experimental bluetooth button controller is temporarily disabled
    - The current size of the application is 23 kilobytes

### Statistics
**Please send information about devices where the program was tested only in this format:**
```
Device type, Manufacturer and Model, Android Version, Kernel Version
```

### Verified devices
- Phones
    - Asus ZC553KL, Android 8.1, Kernel 3.18.71
    - Blackview BV4900Pro, Android 12, Kernel 4.19.191
    - Samsung Galaxy A51, Android 13, Kernel 4.14.113
    - Samsung Galaxy J7, Android 10 , Kernel 3.18.150
    - Samsung S8, Android 9, Kernel 4.4.153
    - Samsung S23 Ultra, Android 15, Kernel 5.15.153
    - Xiaomi Redmi 7A, Android 10, Kernel 4.9.261
    - Xiaomi Redmi Note 13 4G, Android 13, Kernel 5.15.94
- Tablets
    - Lenovo TB-X304L, Android 8.1.0, Kernel 3.18.71 (the image is cut off at the edges)
    - Lenovo TB-X606F, Android 10, Kernel unknown (the image is cut off at the edges)
    - Lenovo Tab P11, Android 11, Kernel 4.19.157-perf+ (the image is cut off at the edges)
    - Samsung Galaxy Tab A, Android 11, Kernel 4.9.227
- TV and Boxes
    - A95X F3 AIR, Android 9, Kernel 4.9.113, SlimBOXtv AOSP 9.14 without GAPPS
    - IE X3 Air, Android 9, Kernel 4.9.113, SlimBOXtv AOSP 9.14 without GAPPS
    - Haier Candy Android TV 2K, Android 11, Kernel 4.9.243+ (no icon in apps on device)
    - SberBox SBDV-00001, Custom ?, Kernel 4.9.228
    - TV Box X96Q, Android 10, Kernel 4.9.170 (original H313 SoC)
- Car Devices
    - Media Center JCAC10003, Android 12, Kernel 3.18.79+ (SoC ac8227/ac8229)
    - Mirror Z55, Android 8.1.0, Kernel 4.4.83

### Incompatible devices
- Tablets
    - China fake Samsung Tablet, Android 4.2.2, Kernel 3.4.0+ (Error parsing package)
    - Pipo M6 Pro Android 4.4.2 Kernel 3.4.0 (Error parsing package)
    - Ployer Momo8, Android 4.1.1 ,Kernel 3.0.8+ (Error parsing package)
    - Samsung Galaxy Tab A, Android 7.1.1, Kernel 3.10.49 (Error parsing package)
- TV and Boxes
    - Eltex NV-501-Wac, Android 4.4.4, Kernel 3.10.24
    - TV box, Android 5.1.1, Kernel 3.14.29
    - Yandex TV, Android 7.1.1, Kernel 4.4.3 (wrong install)

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
