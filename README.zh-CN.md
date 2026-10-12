<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://openipc.org/assets/openipc-logo-white.svg">
  <source media="(prefers-color-scheme: light)" srcset="https://openipc.org/assets/openipc-logo-black.svg">
  <img alt="OpenIPC logo" src="https://openipc.org/assets/openipc-logo-black.svg">
</picture>

# OpenIPC Decoder

[English](README.md) | [Русский](README.ru.md) | **简体中文**

[![Build](https://github.com/OpenIPC/decoder/actions/workflows/build.yml/badge.svg)](https://github.com/OpenIPC/decoder/actions/workflows/build.yml)
[![Release](https://img.shields.io/badge/download-latest-blue)](https://github.com/OpenIPC/decoder/releases/tag/latest)
[![License](https://img.shields.io/badge/license-Prosperity%203.0-blue)](LICENSE.md)
[![Platform](https://img.shields.io/badge/platform-Android%205.0%2B-brightgreen)](https://developer.android.com/about/versions/lollipop)

一款小巧的 Android 应用，用于观看 IP 摄像机的 RTSP 视频流，支持 H.264/H.265 硬件解码。专为运行 [OpenIPC](https://openipc.org) 固件的摄像机设计，可在手机、平板、电视、电视盒子和车载设备上使用。

<p align="center">
  <img src="screenshots/photo_menu.jpg" alt="应用截图" width="640">
</p>

## 下载

可直接安装的版本位于 [**latest** 发布页](https://github.com/OpenIPC/decoder/releases/tag/latest)：

| 文件             | 说明                                    |
|------------------|-----------------------------------------|
| `decoder-v2.apk` | **当前版本**，推荐使用                  |
| `decoder.apk`    | `basic` 分支的旧版本                    |
| `*.aab`          | 用于 Google Play 的应用包，不能手动安装 |

[Google Play](https://play.google.com/apps/internaltest/4701231141824898735) 上提供测试渠道。如需加入，请通过 [OpenIPC Telegram 频道](https://openipc.org/our-channels)发送您的电子邮箱。

## 功能

- **视频：** RTSP 播放，通过 MediaCodec 进行 H.264/H.265 硬件解码
- **音频：** AAC（RFC 3640 `mpeg4-generic`、ADTS）、G.711 μ-law/A-law、L16 PCM（单声道/立体声）
- **显示：** 单路画面，或 Quad 四分屏（同时显示 4 路摄像机）
- **操作：** 双指缩放、拖动平移、双击复位，支持 Android TV 遥控器（Leanback 启动器）
- **网络：** 支持 Basic 和 Digest 认证、RTSP 保活，断线后自动重连（指数退避），连接质量指示
- **WebUI：** 内置浏览器，可打开摄像机的网页管理界面
- **其他：** 长按截图，4 个摄像机槽位，可接收其他应用分享的地址

## 系统要求

- Android 5.0（API 21）或更高版本
- 支持 RTSP over TCP 的 IP 摄像机（参见[已知限制](#已知限制)）

## 快速上手

1. 从 [latest 发布页](https://github.com/OpenIPC/decoder/releases/tag/latest)安装 APK 并启动应用。
2. 点击屏幕或按遥控器上的 **OK** 键打开菜单。
3. 选择摄像机编号（**1**–**4**），点击 **Settings** 并输入摄像机地址：
   ```
   rtsp://username:password@ip_address:554/stream=0
   ```
4. 点击 **Save**。应用会立即连接；视频流中断时会自动重连。

## 操作说明

### 菜单

| 菜单项       | 功能                                                                                                                                          |
|--------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| **Q**        | 开启或关闭 Quad 四分屏模式                                                                                                                    |
| **1**–**4**  | 切换摄像机槽位。当前槽位编号的颜色表示连接质量：绿色为抖动低于 100 毫秒，黄色为 100–300 毫秒，红色为高于 300 毫秒。灰色编号表示该槽位未配置。 |
| **Settings** | 编辑当前槽位的 RTSP 地址（仅单路模式）                                                                                                        |
| **WebUI**    | 打开摄像机网页界面，用户名和密码取自 RTSP 地址（仅单路模式）                                                                                  |
| **Exit**     | 退出应用。旁边的小字是应用版本号和构建哈希。                                                                                                  |

### 手势与按键

| 输入                      | 功能                            |
|---------------------------|---------------------------------|
| 点击 / **OK** / **Enter** | 打开或关闭菜单                  |
| **Back** / **Esc**        | 关闭菜单                        |
| 双指捏合后拖动            | 缩放（最大 5 倍）并平移         |
| 双击                      | 恢复原始大小                    |
| 长按                      | 截图并保存到 `Pictures/OpenIPC` |

### 从其他应用打开摄像机

应用可接收纯文本分享（`ACTION_SEND`，`text/plain`）。从其他应用（例如 OpenIPC Network）分享 `rtsp://` 地址后，该地址会写入当前槽位。

### Quad 四分屏模式

以 2×2 网格显示所有已配置的槽位。每个画面都有独立的连接和解码器。此模式下没有声音。点击任意画面即可打开菜单。

## 已知限制

> [!IMPORTANT]
> 以下是我们计划解决的已知不足，每一项都有对应的 issue。欢迎留言、提交测试结果和 pull request。
>
> - **仅支持 RTSP over TCP**，不支持 UDP 和组播 — [#7](https://github.com/OpenIPC/decoder/issues/7)
> - **视频的 RTP 处理较为简单：** 不支持 STAP-A/AP 聚合包，带 CSRC 或扩展头的包会被丢弃，也不处理丢包 — [#10](https://github.com/OpenIPC/decoder/issues/10)
> - **Quad 模式下没有声音** — [#12](https://github.com/OpenIPC/decoder/issues/12)
> - **仅有 4 个摄像机槽位** — [#13](https://github.com/OpenIPC/decoder/issues/13)
> - **不支持的音频格式：** MP4A-LATM、Opus、G.726，以及跨多个 RTP 包分片的 AAC 帧 — [#14](https://github.com/OpenIPC/decoder/issues/14)
> - **部分设备上可能依赖 Google 服务（GAPPS）**，仍在调查中，请谨慎禁用 GAPPS — [#15](https://github.com/OpenIPC/decoder/issues/15)
> - **用户名和密码以明文存储和传输**（未加密的 RTSP/HTTP；摄像机未要求 Digest 时发送 Basic） — [#16](https://github.com/OpenIPC/decoder/issues/16)
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

| 类                            | 作用                                                     |
|-------------------------------|----------------------------------------------------------|
| `Decoder`                     | 主 Activity：界面、生命周期、设置、视频解码线程          |
| `RtspClient`                  | RTSP 握手、RTP over TCP 接收循环、看门狗、SDP 解析       |
| `NalAssembler`                | 将 RTP 分片重组为 H.264/H.265 NAL 单元                   |
| `MediaCodecManager`           | 硬件视频解码器的生命周期管理                             |
| `AudioManager`、`AudioConfig` | 音频处理：G.711/L16 转换、AAC 解码、通过 AudioTrack 播放 |
| `QuadCell`                    | Quad 模式中单个画面的独立数据流（网络、看门狗、解码器）  |
| `MenuManager`                 | 弹出菜单、地址编辑器、WebUI 浏览器                       |
| `FramePool`                   | 缓冲区池，减轻垃圾回收压力                               |

网络、视频解码和音频分别运行在独立线程中，解码器较慢时也不会阻塞网络读取。

## 安全说明

- 用户名和密码从地址中提取，通过 `Authorization` 头发送：默认使用 Basic，摄像机要求时改用 Digest。不会出现在 RTSP 请求行中。
- RTSP 和 HTTP 均未加密，请仅在可信网络中使用，参见 [#16](https://github.com/OpenIPC/decoder/issues/16)。
- 摄像机地址（含密码）保存在应用私有存储中，并已禁用备份（`allowBackup="false"`）。

## 设备兼容性

在您的设备上测试过本应用？请[**提交 Device report**](https://github.com/OpenIPC/decoder/issues/new?template=device_report.yml)，只需一分钟，可以帮助其他用户。

### 已验证设备

以下设备的报告基于旧版 1.0x（`basic` 分支）。新报告请注明应用版本。

| 类型     | 设备                                  | Android                            | 内核           | 版本 | 备注                                                                |
|----------|---------------------------------------|------------------------------------|----------------|------|---------------------------------------------------------------------|
| 手机     | Asus ZC553KL                          | 8.1                                | 3.18.71        | 1.0x |                                                                     |
| 手机     | Blackview BV4900Pro                   | 12                                 | 4.19.191       | 1.0x |                                                                     |
| 手机     | Oppo 5X                               | ColorOS 15.0                       | 5.15.149       | 1.0x |                                                                     |
| 手机     | Oppo A17                              | ColorOS 12.1                       | 4.19.191       | 1.0x |                                                                     |
| 手机     | Samsung Galaxy A51                    | 13                                 | 4.14.113       | 1.0x |                                                                     |
| 手机     | Samsung Galaxy M21 (SM-M215F/DSN)     | 12                                 | 4.14.113       | 1.0x |                                                                     |
| 手机     | Samsung Galaxy S25                    | 15                                 | 6.6.30         | 1.0x |                                                                     |
| 手机     | Samsung A55 5G                        | 14                                 | 6.1.93         | 1.0x |                                                                     |
| 手机     | Samsung Galaxy J7                     | 10                                 | 3.18.150       | 1.0x |                                                                     |
| 手机     | Samsung S8                            | 9                                  | 4.4.153        | 1.0x |                                                                     |
| 手机     | Samsung S23 Ultra                     | 15                                 | 5.15.153       | 1.0x |                                                                     |
| 手机     | Xiaomi Redmi 7A                       | 10                                 | 4.9.261        | 1.0x |                                                                     |
| 手机     | Xiaomi Redmi Note 7 (M1901F7G)        | 10                                 | 4.4.192        | 1.0x |                                                                     |
| 手机     | Xiaomi Redmi Note 13 4G               | 13                                 | 5.15.94        | 1.0x |                                                                     |
| 平板     | Lenovo TB-X304L                       | 8.1.0                              | 3.18.71        | 1.0x | 画面边缘被裁切，[#17](https://github.com/OpenIPC/decoder/issues/17) |
| 平板     | Lenovo TB-X606F                       | 10                                 | —              | 1.0x | 画面边缘被裁切，[#17](https://github.com/OpenIPC/decoder/issues/17) |
| 平板     | Lenovo Tab P11                        | 11                                 | 4.19.157-perf+ | 1.0x | 画面边缘被裁切，[#17](https://github.com/OpenIPC/decoder/issues/17) |
| 平板     | Samsung Galaxy Tab A                  | 7.1.1                              | 3.10.49        | 1.0x |                                                                     |
| 平板     | Samsung Galaxy Tab A                  | 11                                 | 4.9.227        | 1.0x |                                                                     |
| 平板     | Samsung Tab A7 (SM-T505)              | 12                                 | 4.19.157-perf- | 1.0x |                                                                     |
| 电视盒子 | A95X F3 AIR                           | 9（SlimBOXtv AOSP 9.14，无 GAPPS） | 4.9.113        | 1.0x |                                                                     |
| 电视盒子 | IE X3 Air                             | 9（SlimBOXtv AOSP 9.14，无 GAPPS） | 4.9.113        | 1.0x |                                                                     |
| 电视盒子 | SberBox SBDV-00001                    | 定制系统                           | 4.9.228        | 1.0x |                                                                     |
| 电视盒子 | X96Q（全志 H313）                     | 10                                 | 4.9.170        | 1.0x |                                                                     |
| 电视     | Haier Candy Android TV 2K             | 11                                 | 4.9.243+       | 1.0x | 应用列表中没有图标                                                  |
| 电视     | Yandex TV Novex NVX-55U169TSY         | 7.1.1                              | 4.4.3          | 1.0x |                                                                     |
| 车载     | 多媒体主机 JCAC10003（ac8227/ac8229） | 12                                 | 3.18.79+       | 1.0x |                                                                     |
| 车载     | 后视镜 Z55                            | 8.1.0                              | 4.4.83         | 1.0x |                                                                     |

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
