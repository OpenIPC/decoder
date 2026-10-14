![OpenIPC logo][logo]

# OpenIPC Decoder

[English](README.md) | [Русский](README.ru.md) | **简体中文**

[![Build](https://github.com/OpenIPC/decoder/actions/workflows/build.yml/badge.svg)](https://github.com/OpenIPC/decoder/actions/workflows/build.yml)
[![Release](https://img.shields.io/badge/download-latest-blue)](https://github.com/OpenIPC/decoder/releases/tag/latest)
[![License](https://img.shields.io/badge/license-Prosperity%203.0-blue)](LICENSE.md)
[![Platform](https://img.shields.io/badge/platform-Android%205.0%2B-brightgreen)](https://developer.android.com/about/versions/lollipop)

一款小巧的 Android 应用，用于观看 IP 摄像机的 RTSP 视频流，支持 H.264/H.265 硬件解码。专为运行 [OpenIPC](https://openipc.org) 固件的摄像机设计，可在手机、平板、电视、电视盒子和车载设备上使用。

<p align="center">
  <img src="screenshots/quad_menu.jpg" alt="多画面模式：八路摄像机与菜单" width="640">
</p>

## 下载

可直接安装的版本位于 [**latest** 发布页](https://github.com/OpenIPC/decoder/releases/tag/latest)：

| 文件             | 说明                   |
|------------------|------------------------|
| `decoder-v2.apk` | **当前版本**，推荐使用 |
| `decoder.apk`    | `basic` 分支的旧版本   |

每个 2.xx 版本另有独立的发布 — [`v2.01`](https://github.com/OpenIPC/decoder/releases/tag/v2.01) 及之后的版本，见[发布页面](https://github.com/OpenIPC/decoder/releases) — 包含该版本的 APK 以及相对上一版本的更改。

[Google Play](https://play.google.com/apps/internaltest/4701231141824898735) 上提供测试渠道。如需加入，请通过 [OpenIPC Telegram 频道](https://openipc.org/our-channels)发送您的电子邮箱。

## 功能

- **视频：** RTSP 播放，通过 MediaCodec 进行 H.264/H.265 硬件解码，支持 RTSP 上的 MJPEG（RTP/JPEG）和 HTTP 上的 MJPEG（`multipart/x-mixed-replace`），单画面和多画面均可，解码器出错后自动恢复，有丢包的 UDP 流以掩盖损坏的画面显示而不是黑屏，设备视频解码器停止响应时给出提示，画面适配任意屏幕比例
- **音频：** AAC（RFC 3640 `mpeg4-generic`、ADTS、MP4A-LATM）、Opus、G.711 μ-law/A-law、L16 PCM（单声道/立体声）；多画面模式下播放所选画面的声音
- **显示：** 单路画面，或多画面模式（Quad），以 2×2 或 3×3 网格最多显示 8 路摄像机外加 U 画面（主动推送 RTP 的摄像机）；自适应解码（每个画面全帧率或仅关键帧，上限按设备实测）；S：仅解码关键帧，多画面和单画面均适用
- **操作：** 双指缩放、拖动平移、双击复位，支持 Android TV 遥控器（Leanback 启动器）
- **网络：** RTSP over TCP 或 UDP（默认 Auto：UDP 被拒绝、无视频或关键帧持续丢失时回退到 TCP），连接时显示实际使用的传输方式、RTSP 组播、接收摄像机通过 UDP 主动推送的 RTP（单播或组播），支持 Basic 和 Digest 认证、RTSP 保活，断线后自动重连（指数退避），连接质量指示
- **WebUI：** 内置浏览器，可打开摄像机的网页管理界面
- **其他：** 8 个摄像机槽位，地址加密保存，长按截图，可接收其他应用分享的地址

## 系统要求

- Android 5.0（API 21）或更高版本
- 支持 RTSP 的 IP 摄像机，或能通过 UDP 主动推送 RTP 的摄像机（OpenIPC Majestic `outgoing`），参见[传输方式](#传输方式)
- **不**需要 Google 服务（GAPPS）：应用可在没有 GAPPS 的 AOSP 系统上运行。WebUI 使用系统 WebView，因此需要可用的 WebView 软件包（部分设备上为 Google 的 *Android System WebView*）；禁用它只影响 WebUI — [#15](https://github.com/OpenIPC/decoder/issues/15)

## 快速上手

1. 从 [latest 发布页](https://github.com/OpenIPC/decoder/releases/tag/latest)安装 APK 并启动应用。
2. 点击屏幕或按遥控器上的 **OK** 键打开菜单。
3. 选择摄像机编号（**1**–**8**），点击 **Settings** 并输入摄像机地址：
   ```
   rtsp://username:password@ip_address:554/stream=0
   ```
   地址下方可选择[传输方式](#传输方式)，新槽位默认为 Auto（优先 UDP，必要时回退到 TCP）。
4. 点击 **Save**。应用会立即连接；视频流中断时会自动重连。

## 操作说明

### 菜单

| 菜单项       | 功能                                                                                                                                          |
|--------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| **1**–**8**  | 切换摄像机槽位。当前槽位编号的颜色表示连接质量：绿色为抖动低于 100 毫秒，黄色为 100–300 毫秒，红色为高于 300 毫秒。灰色编号表示该槽位未配置。 |
| **U**        | 全屏显示 U 画面：通过 UDP 向 5600 端口推送 RTP 的摄像机                                                                                       |
| **D**        | 在每个多画面格子的角落显示文字（分辨率、编码、已解码/已接收帧率、关键帧、状态），或全部不显示                                                 |
| **Q**        | 视图：多画面（Quad）或单路画面。Q 不改变播放模式（S） |
| **S**        | 播放模式：全部帧或仅关键帧（画面按关键帧间隔更新，MJPEG 每秒一帧），解码器负载最低。多画面和单画面均适用，不切换视图 |
| **Settings** | 编辑当前槽位的地址和传输方式（仅单路模式）                                                                                                    |
| **WebUI**    | 打开摄像机网页界面，用户名和密码取自 RTSP 地址（仅单路模式）                                                                                  |
| **Reset**    | 确认后清除所有设置（默认焦点在 **Cancel**），以便从头重新配置：删除摄像机槽位、传输方式和其他设置，应用像刚安装后一样打开。摄像机本身不受影响 |
| **Exit**     | 退出应用。旁边的小字是应用版本号和构建哈希。                                                                                                  |

已开启的项目（D、Q、S、U 和当前槽位）以橙色填充；遥控器焦点所在的项目带浅蓝色边框。

### 手势与按键

| 输入                      | 功能                                                                |
|---------------------------|---------------------------------------------------------------------|
| 点击 / **OK** / **Enter** | 打开或关闭菜单                                                      |
| **Back** / **Esc**        | 关闭菜单                                                            |
| 双指捏合后拖动            | 缩放（最大 5 倍）并平移，仅单路画面                                 |
| 双击                      | 恢复原始大小                                                        |
| 长按                      | 单路画面：截图并保存到 `Pictures/OpenIPC`。多画面：播放该画面的声音 |

### 从其他应用打开摄像机

应用可接收纯文本分享（`ACTION_SEND`，`text/plain`）。从其他应用（例如 OpenIPC Network）分享 `rtsp://`、`udp://` 或 `http://` 地址后，该地址会写入当前槽位。可选参数 `transport`（`tcp`、`udp`、`auto`、`multicast`）用于设置该槽位的传输方式：

```sh
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT 'rtsp://user:pass@192.168.1.10/stream=0' \
  --es transport udp -n com.openipc.decoder/.Decoder
```

### 多画面模式（Quad）

以 2×2 网格显示已配置的槽位；只要使用了第 5–8 个槽位中的任意一个，就改为 3×3 网格。3×3 网格的第九格即 **U** 画面，播放通过 UDP 向本设备 5600 端口主动推送 RTP 的摄像机（`udp://@:5600`，参见 [udp:// 槽位](#udp-槽位-majestic-outgoing)）；收到流之前，其中列出本设备可用于推流的所有地址。菜单中的 **U** 可将其全屏显示。其他端口或组播组请在槽位中填写 `udp://` 地址。每个画面都有独立的连接和解码器，而设备视频解码器的处理能力是固定的：例如测试用的 AG-216 盒子以全帧率大约只能解码两路 1080p30。因此网格会自适应：各画面先只解码关键帧，然后按优先级（正在接收流的 U 画面，其次是正在播放声音的画面，再按槽位顺序）在解码器跟得上时切换为全帧率。其余画面保持只解码关键帧，画面按关键帧间隔更新（角落文字显示 `key frames`，菜单 **D**）。上限在每台设备上通过实测确定。如摄像机提供子码流，可让更多画面以全帧率播放。超出硬件解码器实例上限（AG-216 为 4 × H.265）的仅关键帧画面改用软件解码器（角落显示 `· software`）；只有连这一预算也超出时才显示 `Decoder limit: N × H.265`。如果连关键帧都处理不过来，优先级最低的画面会被暂停（`Paused: decoder overloaded`），平稳一段时间后再重试；网格启动后最初几秒（所有解码器同时启动）的丢帧不计入。若某画面的 UDP 流每个关键帧都丢失一部分，会显示掩盖了损坏的画面（`· damaged`），而不是黑屏。部分摄像机同一时间只允许一个 RTSP 客户端。长按某个画面即可播放其声音（再次长按则静音）；被选中的画面显示 AUDIO 标记，选择会被记住。点击任意画面即可打开菜单。

## 传输方式

每个槽位可单独设置传输方式：在 **Settings** 中地址下方选择（或通过分享链接的 `transport` 参数设置）。

| 传输方式        | 视频如何到达                                | 说明                                                                                                                    |
|-----------------|---------------------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| **TCP**         | RTP 封装在 RTSP 连接中                      | 适用于所有环境，也可穿过 NAT 和防火墙                                                                                   |
| **UDP**         | 通过 UDP 传输 RTP，每路一对端口             | 局域网内延迟更低。丢失的包直接跳过，不会重传。不会回退到 TCP                                                            |
| **Auto**（默认） | 优先 UDP，UDP 不可用时改用 TCP              | 摄像机拒绝 UDP 或 3 秒内未收到视频时回退到 TCP，并保持 TCP 直到修改该槽位                                               |
| **Multicast**   | RTSP 组播（Android 8.0+），被拒绝时改用 TCP | 适用于提供 RTSP 组播的服务器。OpenIPC Majestic 和 XM 摄像机不支持：它们返回 400 或 451，槽位会在同一连接中改用 TCP 播放 |

实际使用的传输方式会在单画面模式开始播放时显示（`Connected • UDP • H.265 …`），也会显示在调试页面和 `/stats.json` 中（`transport`；槽位的设置为 `transport_mode`）。回退后会显示为例如 `TCP (auto)`。

<p align="center">
  <img src="screenshots/single_status.jpg" alt="单画面刚连接时：传输方式、编码和码率" width="640">
</p>

### UDP 槽位 (Majestic outgoing)

槽位也可以接收摄像机不经 RTSP 主动推送的 RTP。为此请填写 `udp://` 地址代替 `rtsp://`：

| 地址                                | 含义                                   |
|-------------------------------------|----------------------------------------|
| `udp://@:5600`                      | 监听 5600 端口                         |
| `udp://@239.1.2.3:5600`             | 加入 239.1.2.3 组播组，端口 5600       |
| `udp://192.168.1.10@239.1.2.3:5600` | 同上，但只接收该发送端的数据包         |
| `udp://@:5600?codec=h265&pt=97`     | 默认值不适用时，指定编码或视频负载类型 |

除非指定 `codec=`，编码根据 RTP 负载类型判断（96 → H.264，97 → H.265）；播放过程中摄像机切换编码也会自动跟随。仅支持视频。两个槽位不能监听同一端口。

在摄像机上，将本设备添加为 `outgoing` 的目标（编辑 `/etc/majestic.yaml` 后重启 Majestic）：

```yaml
outgoing:
  servers:
    - udp://192.168.1.20:5600
```

摄像机上的 `/api/v1/outgoing.json` 可查看推流是否正常。Majestic 也可以推送到组播组（`udp://239.1.2.3:5600`），这样多台设备可以同时观看同一台摄像机。在 Wi‑Fi 下，应用加入组播组期间会持有 multicast lock。

### HTTP MJPEG 槽位

槽位也可以填写 HTTP MJPEG 地址，例如 Majestic 的 `http://user:password@<摄像机>/mjpeg`：`multipart/x-mixed-replace` 流，每个部分是一帧 JPEG，可以是分块传输（chunked）。Basic 凭据随请求发送，摄像机要求时使用 Digest；断线后与 RTSP 一样自动重连。单画面和多画面均可播放，传输方式显示为 `HTTP`。此方式没有音频。

## 调试页面（端口 8085）

应用运行时会在 **8085** 端口通过 HTTP 提供调试信息，可在同一网络中用浏览器或 `curl` 访问：

- `http://<设备>:8085/` — 快照铺满页面，每个画面上叠加摄像机、分辨率、帧率和丢帧（红色）；页头显示版本、设备时间和运行时长。页头的 **Info On / Off** 开关可隐藏画面上的文字（以及窄屏时下方的列表），只保留画面；默认为 **Off**，选择会保存在浏览器中。设备视频解码器停止响应时，页头会出现红色提示（`decoder_stuck`）；
- `http://<设备>:8085/snapshot.jpg`（`?labels=0`：仅画面）— 按屏幕布局拼成的图片，分辨率与屏幕相同（最大 1920×1080），取自应用当前显示的画面；没有视频的画面为黑色并显示其状态。仅在请求时截取；页面在标签页可见时每 3、5（默认）或 10 秒刷新一次；
- `http://<设备>:8085/stats.json` — 相同内容的 JSON，便于脚本使用；
- 单画面模式下，页面和快照仍显示完整网格（#100）：屏幕上的摄像机用蓝框标出；页面打开期间，其他每个已配置的槽位约每 45 秒进行一次简短的流检测（连接、接收几秒数据包、不解码），其画面显示检测结果，例如 `Stream OK · H.265 · 25 fps · 3.1 Mbit/s · checked 12 s ago`，或错误信息。与屏幕上相同的摄像机的槽位不检测（部分摄像机只接受一个客户端），`udp://` 槽位也不检测。`stats.json` 中这些画面位于 `slots` 字段；
- `http://<设备>:8085/log` — 应用日志的最后 500 行。

仅响应本地网络中的客户端（loopback、10/8、172.16/12、192.168/16、链路本地地址、IPv6 fc00::/7），其他地址返回 403。不会显示用户名和密码。

<p align="center">
  <img src="screenshots/debug_page.jpg" alt="调试页面：快照及每个画面的文字" width="640"><br>
  <img src="screenshots/debug_json.png" alt="/stats.json" width="318">
  <img src="screenshots/debug_log.png" alt="/log" width="318">
</p>

## 已知限制

> [!IMPORTANT]
> 每一项都有对应的 issue。欢迎留言、提交测试结果和 pull request。
>
> - **RTSP 组播需要 Android 8.0+**，且服务器须支持；OpenIPC 摄像机不支持 — [#69](https://github.com/OpenIPC/decoder/issues/69)
> - **不支持 G.726 音频，也不支持流内配置的 MP4A-LATM（`cpresent=1`）。** OpenIPC Majestic 不使用这些格式，支持 G.726 的摄像机通常也能输出 G.711 或 AAC — [#14](https://github.com/OpenIPC/decoder/issues/14)
> - **在没有可用 JPEG 解码器的设备上 MJPEG 为软件解码**（AG-216 声明了 JPEG 解码器但无法创建）；设备提供 `video/jpeg` 时使用硬件解码 — [#132](https://github.com/OpenIPC/decoder/issues/132)
> - **AG-216 的视频解码器可能停止响应，直到重启设备**：帧照常到达但无法解码；应用会在屏幕和调试页面上提示，原因尚不清楚 — [#145](https://github.com/OpenIPC/decoder/issues/145)
> - **在多画面与单画面之间切换时，部分电视盒子（如 AG-216）的画面可能会冻结数秒** — [#76](https://github.com/OpenIPC/decoder/issues/76)
> - **用户名和密码以明文传输**（未加密的 RTSP/HTTP；摄像机未要求 Digest 时发送 Basic），不支持 RTSPS，也不计划支持 — [#16](https://github.com/OpenIPC/decoder/issues/16)
>
> 已知的程序错误另行收录在 [`bug` 标签](https://github.com/OpenIPC/decoder/issues?q=is%3Aissue+is%3Aopen+label%3Abug)下。

## 分支

| 分支    | 说明                                                                                 |
|---------|--------------------------------------------------------------------------------------|
| `v2`    | **主开发分支**（默认）。模块化代码位于 `android/`，构建产物发布为 `decoder-v2.apk`。 |
| `basic` | 旧版 1.0x，单文件实现，保留作参考并供旧环境使用。构建产物发布为 `decoder.apk`。      |

请基于 `v2` 分支提交 pull request。

## 构建

需要 JDK 17 和 Android SDK（platform 36）。通过 `ANDROID_HOME` 或 `android/local.properties` 指定 SDK 路径。

```bash
cd android
./gradlew assembleDebug        # 调试版本，无需签名密钥
# → app/build/outputs/apk/debug/app-debug.apk
```

发布版本需要密钥库 `android/key.jks` 以及以下环境变量：

```bash
export KEYSTORE_PASS=your_password
export KEY_ALIAS=your_alias
export KEY_PASS=your_key_password
./gradlew assembleRelease      # → app/build/outputs/apk/release/app-release.apk
```

调试版本和发布版本使用不同的密钥签名。在两者之间切换前需先卸载应用，已保存的摄像机地址会一并删除。

**CI：** GitHub Actions 会构建每一次 push。推送到 `v2` 和 `basic` 时会更新 [latest](https://github.com/OpenIPC/decoder/releases/tag/latest) 发布页并通知 OpenIPC Telegram 频道。其他分支的 APK 只作为工作流产物附在运行页面上。

## 架构

| 类                                       | 作用                                                                      |
|------------------------------------------|---------------------------------------------------------------------------|
| `Decoder`                                | 主 Activity：界面、生命周期、设置、单路视频解码、多画面网格               |
| `RtspClient`                             | 单路数据流：RTSP 会话、NAL 重组、音频、连接质量                           |
| `RtspSession`                            | 单个 RTSP 会话（TCP、UDP 或组播）：握手、RTP 接收、保活、看门狗、退避重连 |
| `RtspAuth`、`Sdp`                        | Basic 与 Digest 认证；解析摄像机的会话描述                                |
| `Transport`、`UdpReceiver`、`RtpReorder` | 传输方式协商；带发送端过滤的 UDP 与组播接收；数据包重排序                 |
| `RtpListener`                            | `udp://` 槽位：接收摄像机主动推送的 RTP                                   |
| `HttpMjpeg`                              | `http://` 槽位：HTTP 上的 MJPEG（`multipart/x-mixed-replace`）            |
| `NalAssembler`                           | 将 RTP 包重组为 H.264/H.265 NAL 单元，丢弃因丢包而损坏的单元              |
| `JpegAssembler`                          | MJPEG：由 RTP/JPEG（RFC 2435）重组完整的 JPEG 帧                          |
| `JpegCodec`、`JpegRenderer`              | MJPEG 显示：有设备 JPEG 解码器时使用它，否则使用 BitmapFactory            |
| `DecodePlanner`                          | 多画面：在解码器实测能力内决定哪些画面全帧率、哪些仅解码关键帧            |
| `MediaCodecManager`、`DecoderRetry`      | 硬件视频解码器的生命周期管理与出错后的恢复                                |
| `AudioManager`、`AudioConfig`            | 音频处理：G.711/L16 转换、AAC/Opus 解码、通过 AudioTrack 播放             |
| `QuadCell`                               | 多画面中的单个画面：独立的会话、解码器和可选音频                          |
| `MenuManager`                            | 弹出菜单、地址编辑器、WebUI 浏览器                                        |
| `CredentialStore`、`KeystoreCipher`      | 摄像机地址加密存储（Android Keystore）                                    |
| `FramePool`、`Frame`                     | 缓冲区池，减轻垃圾回收压力                                                |
| `App`、`Crash`                           | 未捕获异常处理与崩溃报告界面                                              |
| `DebugServer`、`NetInfo`、`Snapshots`    | 8085 端口调试页面（仅本地网络）、设备地址、屏幕快照                           |

网络、视频解码和音频分别运行在独立线程中，解码器较慢时也不会阻塞网络读取。

## 安全说明

- 用户名和密码从地址中提取，通过 `Authorization` 头发送：默认使用 Basic，摄像机要求时改用 Digest。不会出现在 RTSP 请求行中。
- RTSP、UDP 上的 RTP 和 HTTP 均未加密，不支持 RTSPS。若摄像机要求将用户名和密码写在地址路径或参数中（例如 `user=…_password=…`），它们会随每个请求发送。请仅在可信网络中使用，参见 [#16](https://github.com/OpenIPC/decoder/issues/16)。
- 摄像机地址（含密码）加密保存在应用私有存储中（AES-256-GCM，密钥保存在 Android Keystore，Android 6.0 及以上），在 Android 5.x 或 Keystore 不可用时以明文保存，并已禁用备份（`allowBackup="false"`）。
- 8085 端口上的调试页面未加密、无密码；仅响应本地网络客户端，且不会显示用户名和密码。

## 设备兼容性

在您的设备上测试过本应用？请[**提交 Device report**](https://github.com/OpenIPC/decoder/issues/new?template=device_report.yml)，只需一分钟，可以帮助其他用户。 测试过某款摄像机？请[**提交 Camera report**](https://github.com/OpenIPC/decoder/issues/new?template=camera_report.yml)：厂商、型号、RTSP 地址（去掉密码）、编码以及哪些功能正常。

### 已验证设备

以下大多数设备的报告基于旧版 1.0x（`basic` 分支）；“版本”一列为实际测试的版本。新报告请注明应用版本。

| 类型     | 设备                                  | Android                            | 内核           | 版本    | 备注                                                                                    |
|----------|---------------------------------------|------------------------------------|----------------|---------|-----------------------------------------------------------------------------------------|
| 手机     | Asus ZC553KL                          | 8.1                                | 3.18.71        | 1.0x    |                                                                                         |
| 手机     | Blackview BV4900Pro                   | 12                                 | 4.19.191       | 1.0x    |                                                                                         |
| 手机     | Oppo 5X                               | ColorOS 15.0                       | 5.15.149       | 1.0x    |                                                                                         |
| 手机     | Oppo A17                              | ColorOS 12.1                       | 4.19.191       | 1.0x    |                                                                                         |
| 手机     | Samsung Galaxy A51                    | 13                                 | 4.14.113       | 1.0x    |                                                                                         |
| 手机     | Samsung Galaxy M21 (SM-M215F/DSN)     | 12                                 | 4.14.113       | 1.0x    |                                                                                         |
| 手机     | Samsung Galaxy S25                    | 15                                 | 6.6.30         | 1.0x    |                                                                                         |
| 手机     | Samsung A55 5G                        | 14                                 | 6.1.93         | 1.0x    |                                                                                         |
| 手机     | Samsung Galaxy J7                     | 10                                 | 3.18.150       | 1.0x    |                                                                                         |
| 手机     | Samsung S8                            | 9                                  | 4.4.153        | 1.0x    |                                                                                         |
| 手机     | Samsung S23 Ultra                     | 15                                 | 5.15.153       | 1.0x    |                                                                                         |
| 手机     | TECNO KM4                             | 15                                 | 5.15.194       | v2 2.04 | 实验室触屏设备：已验证 WebUI 与屏幕键盘（[#159](https://github.com/OpenIPC/decoder/issues/159)、[#160](https://github.com/OpenIPC/decoder/issues/160)）；Close 按钮被状态栏遮挡（[#169](https://github.com/OpenIPC/decoder/issues/169)） |
| 手机     | Xiaomi Redmi 7A                       | 10                                 | 4.9.261        | 1.0x    |                                                                                         |
| 手机     | Xiaomi Redmi Note 7 (M1901F7G)        | 10                                 | 4.4.192        | 1.0x    |                                                                                         |
| 手机     | Xiaomi Redmi Note 13 4G               | 13                                 | 5.15.94        | 1.0x    |                                                                                         |
| 平板     | Lenovo TB-8504X                       | 8.1.0                              | 3.18.71        | v2 2.00 | H.265 与多画面正常；WebUI 输入问题已在 2.02 修复（[#159](https://github.com/OpenIPC/decoder/issues/159)） |
| 平板     | Lenovo TB-X304L                       | 8.1.0                              | 3.18.71        | 1.0x    | 1.0x 中画面边缘被裁切，v2 已修复（[#17](https://github.com/OpenIPC/decoder/issues/17)） |
| 平板     | Lenovo TB-X606F                       | 10                                 | —              | 1.0x    | 1.0x 中画面边缘被裁切，v2 已修复（[#17](https://github.com/OpenIPC/decoder/issues/17)） |
| 平板     | Lenovo Tab P11                        | 11                                 | 4.19.157-perf+ | 1.0x    | 1.0x 中画面边缘被裁切，v2 已修复（[#17](https://github.com/OpenIPC/decoder/issues/17)） |
| 平板     | Samsung Galaxy Tab A                  | 7.1.1                              | 3.10.49        | 1.0x    |                                                                                         |
| 平板     | Samsung Galaxy Tab A                  | 11                                 | 4.9.227        | 1.0x    |                                                                                         |
| 平板     | Samsung Tab A7 (SM-T505)              | 12                                 | 4.19.157-perf- | 1.0x    |                                                                                         |
| 电视盒子 | A95X F3 AIR                           | 9（SlimBOXtv AOSP 9.14，无 GAPPS） | 4.9.113        | 1.0x    |                                                                                         |
| 电视盒子 | IE X3 Air                             | 9（SlimBOXtv AOSP 9.14，无 GAPPS） | 4.9.113        | 1.0x    |                                                                                         |
| 电视盒子 | AG-216                                | 10                                 | 4.9.170        | v2 2.00 | v2 测试设备；实测解码能力约为总计 1080p60（全帧率两路 1080p30）                         |
| 电视盒子 | SberBox SBDV-00001                    | 定制系统                           | 4.9.228        | 1.0x    |                                                                                         |
| 电视盒子 | X96Q（全志 H313）                     | 10                                 | 4.9.170        | 1.0x    |                                                                                         |
| 投影仪   | SIBIRLINK HY300 / HY320               | 11                                 | 4.19.323       | v2 2.02 | 1080p H.264 / H.265 正常；2304×1296 和 2560×1440 无法播放（[#165](https://github.com/OpenIPC/decoder/issues/165)、[#166](https://github.com/OpenIPC/decoder/issues/166)） |
| 电视     | Haier Candy Android TV 2K             | 11                                 | 4.9.243+       | 1.0x    | 应用列表中没有图标                                                                      |
| 电视     | Yandex TV Novex NVX-55U169TSY         | 7.1.1                              | 4.4.3          | 1.0x    |                                                                                         |
| 车载     | 多媒体主机 JCAC10003（ac8227/ac8229） | 12                                 | 3.18.79+       | 1.0x    |                                                                                         |
| 车载     | 后视镜 Z55                            | 8.1.0                              | 4.4.83         | 1.0x    |                                                                                         |

### 无法使用

| 类型     | 设备             | Android | 内核    | 版本 | 备注   |
|----------|------------------|---------|---------|------|--------|
| 电视盒子 | 未知型号电视盒子 | 5.1.1   | 3.14.29 | 1.0x | 无视频 |

## 版本历史

更新内容请参见[发布页](https://github.com/OpenIPC/decoder/releases)和[提交历史](https://github.com/OpenIPC/decoder/commits/v2)。1.0x 版本的历史记录见 [`basic` 分支的 README](https://github.com/OpenIPC/decoder/tree/basic#version-history)。

## 许可证

[The Prosperity Public License 3.0.0](LICENSE.md)：非商业用途可免费使用；商业用途可试用 30 天。完整条款见 [LICENSE.md](LICENSE.md)。

## 支持

- 问题与错误报告：[GitHub Issues](https://github.com/OpenIPC/decoder/issues)
- 文档：[openipc.org](https://openipc.org)

[![Telegram](https://openipc.org/images/telegram_button.svg)](https://openipc.org/our-channels)

[logo]: https://raw.githubusercontent.com/OpenIPC/.github/refs/heads/main/assets/openipc-logo-black.svg
