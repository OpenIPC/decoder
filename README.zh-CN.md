![OpenIPC logo][logo]

# OpenIPC Decoder

[English](README.md) | [Русский](README.ru.md) | **简体中文**

[![Build](https://github.com/OpenIPC/decoder/actions/workflows/build.yml/badge.svg)](https://github.com/OpenIPC/decoder/actions/workflows/build.yml)
[![Release](https://img.shields.io/badge/download-latest-blue)](https://github.com/OpenIPC/decoder/releases/latest)
[![License](https://img.shields.io/badge/license-Prosperity%203.0-blue)](LICENSE.md)
[![Platform](https://img.shields.io/badge/platform-Android%205.0%2B-brightgreen)](https://developer.android.com/about/versions/lollipop)

一款小巧的 Android 应用，用于观看 IP 摄像机的 RTSP 视频流，支持 H.264/H.265 硬件解码。专为运行 [OpenIPC](https://openipc.org) 固件的摄像机设计，可在手机、平板、电视、电视盒子和车载设备上使用。

- **视频：** 通过 MediaCodec 解码 H.264/H.265，支持 RTSP 和 HTTP 上的 MJPEG；解码器出错后自动恢复
- **音频：** AAC、Opus、G.711、L16
- **显示：** 单路画面，或多画面模式：最多 8 路摄像机外加一路主动推送 RTP 的摄像机，解码方式根据设备能力自动调整
- **网络：** RTSP over TCP、UDP（Auto 模式会回退到 TCP）或组播，接收摄像机主动推送的 RTP（Majestic `outgoing`），支持 Basic 和 Digest 认证，断线自动重连
- **其他：** 支持 Android TV 遥控器、缩放、摄像机网页界面，地址加密保存，端口 8085 上的调试页面

<p align="center">
  <img src="screenshots/quad_menu.jpg" alt="多画面模式：八路摄像机与菜单" width="640">
</p>

<p align="center">
  <img src="screenshots/single_status.jpg" alt="单画面刚连接时：传输方式、编码和码率" width="640">
</p>

<p align="center">
  <img src="screenshots/phone_menu.jpg" alt="手机上的菜单" width="400">
  <img src="screenshots/phone_webui.jpg" alt="手机上的 WebUI：页面会把输入框滚动到键盘上方" width="400">
</p>

<p align="center">
  <img src="screenshots/debug_page.jpg" alt="调试页面：快照及每个画面的文字" width="640"><br>
  <img src="screenshots/debug_json.png" alt="/stats.json" width="318">
  <img src="screenshots/debug_log.png" alt="/log" width="318">
</p>

## 下载

可直接安装的版本位于唯一的发布 [**latest**](https://github.com/OpenIPC/decoder/releases/latest)，标题为 `Decoder <版本>`。文件名不含版本号，因此以下链接始终不变：

| 文件             | 说明                   | 永久链接                                                                     |
|------------------|------------------------|------------------------------------------------------------------------------|
| `decoder-v2.apk` | **当前版本**，推荐使用 | <https://github.com/OpenIPC/decoder/releases/latest/download/decoder-v2.apk> |
| `decoder.apk`    | `basic` 分支的旧版本   | <https://github.com/OpenIPC/decoder/releases/latest/download/decoder.apk>    |

每次新构建都会替换 `latest` 中的上一个构建。开发是持续进行的，因此不再按版本单独发布；`v2` 分支的每个新版本都会以 APK 文件连同提交发布到 OpenIPC 的 Telegram 频道（[我们的频道](https://openipc.org/our-channels)）。

各版本的变更记录见 [CHANGELOG.md](CHANGELOG.md)。

[Google Play](https://play.google.com/apps/internaltest/4701231141824898735) 上提供测试渠道。如需加入，请通过 [OpenIPC Telegram 频道](https://openipc.org/our-channels)发送您的电子邮箱。

## 系统要求

- Android 5.0（API 21）或更高版本
- 支持 RTSP 或 HTTP MJPEG 的 IP 摄像机，或能通过 UDP 主动推送 RTP 的摄像机（OpenIPC Majestic `outgoing`）
- **不**需要 Google 服务（GAPPS）。WebUI 需要可用的系统 WebView（[#15](https://github.com/OpenIPC/decoder/issues/15)）

## 快速上手

1. 从 [latest 发布页](https://github.com/OpenIPC/decoder/releases/latest)安装 APK 并启动应用。
2. 点击屏幕或按遥控器上的 **OK** 键打开菜单。
3. 选择摄像机编号（**1**–**8**），点击 **Settings** 并输入摄像机地址：
   ```
   rtsp://username:password@ip_address:554/stream=0
   ```
   地址下方可选择传输方式，新槽位默认为 Auto（优先 UDP，必要时回退到 TCP）。其下的 **Turn** 可旋转画面，适用于侧装或倒装的摄像机；**Mirror** 可将画面左右镜像。其他地址格式见 [Transports](docs/transports.md)（英文）。
   **Start on boot** 可在设备重启后自动打开应用，适用于只用来显示摄像机画面的盒子：[Start on boot](docs/usage.md#start-on-boot)（英文）。
4. 点击 **Save**。应用会立即连接；视频流中断时会自动重连。

## 操作说明

```
[ 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 ]
[ Settings | WebUI  | Grid   | Slides ]
[ Exit     | Reset  | Debug  | UDP    ]
```

| 输入                  | 功能                                                   |
|-----------------------|--------------------------------------------------------|
| 点击 / **OK**         | 打开或关闭菜单                                         |
| **1**–**8**           | 摄像机槽位；编号的颜色表示连接质量                     |
| **Grid** / **Slides** | 多画面或单路画面 / 解码全部帧或仅关键帧                |
| **Debug** / **UDP**   | 多画面中每个画面的文字 / 向端口 5600 推送 RTP 的摄像机 |
| 双指捏合、双击        | 缩放和平移、恢复原始大小（单路画面）                   |
| 长按                  | 截图（单路画面）/ 播放该画面的声音（多画面）           |

菜单各项、遥控器、从其他应用分享地址以及 **Reset** 的说明见 [Usage](docs/usage.md)（英文）。

## 已知限制

> [!IMPORTANT]
> 每一项都有对应的 issue。欢迎留言、提交测试结果和 pull request。
>
> - **RTSP 组播需要 Android 8.0+**，且服务器须支持；OpenIPC 摄像机不支持 — [#69](https://github.com/OpenIPC/decoder/issues/69)
> - **不支持 G.726 音频，也不支持流内配置的 MP4A-LATM（`cpresent=1`）。** OpenIPC Majestic 不使用这些格式，支持 G.726 的摄像机通常也能输出 G.711 或 AAC — [#14](https://github.com/OpenIPC/decoder/issues/14)
> - **在没有可用 JPEG 解码器的设备上 MJPEG 为软件解码**（AG-216 声明了 JPEG 解码器但无法创建）— [#132](https://github.com/OpenIPC/decoder/issues/132)
> - **AG-216 的视频解码器可能停止响应，直到重启设备**：帧照常到达但无法解码；应用会在屏幕和调试页面上提示 — [#145](https://github.com/OpenIPC/decoder/issues/145)
> - **用户名和密码以明文传输**（未加密的 RTSP/HTTP；摄像机未要求 Digest 时发送 Basic），不支持 RTSPS，也不计划支持 — [#16](https://github.com/OpenIPC/decoder/issues/16)
> - **截图和调试页面快照需要 Android 7.0+**
>
> 已知的程序错误另行收录在 [`bug` 标签](https://github.com/OpenIPC/decoder/issues?q=is%3Aissue+is%3Aopen+label%3Abug)下。

## 文档

文档以英文撰写，以下链接均指向英文文档。

| 文档                                    | 内容                                                                                                                              |
|-----------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------|
| [Usage](docs/usage.md)                  | 菜单、手势与按键、遥控器、多画面、Slides、U 画面、WebUI、从其他应用分享地址、截图、Reset                                          |
| [Transports](docs/transports.md)        | 地址格式与参数：RTSP over TCP/UDP/Auto/组播、`udp://` 推流接收（Majestic `outgoing`）、HTTP MJPEG；编码、认证、各服务器的支持情况 |
| [Debug](docs/debug.md)                  | 端口 8085 上的调试页面、`/stats.json` 字段说明、`/log`、快照、提交错误报告时需附带的内容                                          |
| [Architecture](docs/architecture.md)    | 数据流程、类、线程、解码调度器、SurfaceView 渲染、仓库结构                                                                        |
| [Testing and releases](docs/testing.md) | 分支、CI、单元测试、版本与发布、分支构建、`mapping.txt`、本地构建                                                                 |
| [Security](docs/security.md)            | 凭据存储、调试服务器（仅限局域网）、日志脱敏、网络传输未加密                                                                      |
| [Devices](docs/devices.md)              | 已验证和无法使用的设备、硬件解码器说明、设备与摄像机报告                                                                          |
| [Drones and Wi‑Fi toys](docs/toys.md)   | 玩具如何传输视频、FLOW-UFO、KY UFO 和 Jieli 无人机、遇到未知玩具时可以尝试什么、需要提交哪些信息                                  |

更新历史即[已合并的 pull request](https://github.com/OpenIPC/decoder/pulls?q=is%3Apr+is%3Amerged+base%3Av2) 和 [v2 分支的提交](https://github.com/OpenIPC/decoder/commits/v2)。

## 支持

- 问题与错误报告：[GitHub Issues](https://github.com/OpenIPC/decoder/issues)
- 在设备或摄像机上测试过本应用？欢迎提交[设备报告](https://github.com/OpenIPC/decoder/issues/new?template=device_report.yml)或[摄像机报告](https://github.com/OpenIPC/decoder/issues/new?template=camera_report.yml)
- OpenIPC：[openipc.org](https://openipc.org)

[![Telegram](https://openipc.org/images/telegram_button.svg)](https://openipc.org/our-channels)

## 许可证

[The Prosperity Public License 3.0.0](LICENSE.md)：非商业用途可免费使用；商业用途可试用 30 天。完整条款见 [LICENSE.md](LICENSE.md)。

[logo]: https://raw.githubusercontent.com/OpenIPC/.github/refs/heads/main/assets/openipc-logo-black.svg
