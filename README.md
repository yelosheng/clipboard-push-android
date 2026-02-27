# Clipboard Push — Android

> Sync your clipboard between Android and PC in real time, with end-to-end encryption.

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-purple.svg)](https://developer.android.com/jetpack/compose)

[中文说明](#中文说明) | [English](#english)

---

## English

### What is Clipboard Push?

Clipboard Push syncs clipboard content (text and files) between your Android device and your PC instantly. Copy something on your phone — it appears on your PC, and vice versa. No account required, no cloud storage of your data.

**Links:**
- Website: [clipboardpush.com](https://clipboardpush.com)
- PC client (Windows): [clipboard-push-win32](https://github.com/clipboardpush/clipboard-push-win32)
- Relay server: [clipboard-push-server](https://github.com/clipboardpush/clipboard-push-server)
- Privacy policy: [clipboardpush.com/privacy](https://clipboardpush.com/privacy)

### Features

- **Real-time sync** — text clipboard syncs the moment you copy
- **File transfer** — send images, documents, and files from your phone to PC
- **AES-256-GCM encryption** — all content is encrypted on-device before transmission; the relay server sees only ciphertext
- **LAN direct transfer** — files transfer directly over your local network when both devices are on the same Wi-Fi (faster, no cloud round-trip)
- **No account required** — pair by scanning a QR code from the PC client
- **Self-hostable** — run your own relay server with Docker in minutes
- **Open source** — audit the code yourself; no hidden data collection

### Quick Start

1. Install the Android app from [Google Play](https://play.google.com/store/apps/details?id=com.clipboardpush.plus) or download the APK from [Releases](https://github.com/clipboardpush/clipboard-push-android/releases)
2. Download and install the [PC client](https://github.com/clipboardpush/clipboard-push-win32/releases) (Windows)
3. Open the Android app → tap **Connect** → tap the QR icon → scan the QR code shown in the PC client
4. Start copying — content syncs automatically

### Architecture Overview

```
Android App  ←──────────────────────────────→  PC Client
                   Socket.IO (relay server)
                         OR
                   LAN direct HTTP pull
```

- **Text sync**: Android encrypts text with AES-256-GCM and sends to the relay server; the PC client receives and decrypts.
- **File transfer**: Android announces file availability; the PC client attempts a direct LAN download first, falling back to a cloud relay upload/download if LAN fails.
- **Pairing**: Devices share a room ID and encryption key by scanning a QR code. No server ever sees the plaintext key.

For a deeper dive, see [CLAUDE.md](CLAUDE.md) (architecture reference) and [LAN_SIGNAL_PROTOCOL.md](LAN_SIGNAL_PROTOCOL.md).

### Building from Source

**Requirements:**
- Android Studio Hedgehog or newer
- JDK 17
- Android SDK with API 34

```bash
git clone https://github.com/clipboardpush/clipboard-push-android.git
cd clipboard-push-android

# Build debug APK
./gradlew assembleDebug

# Run unit tests (Robolectric, no device needed)
./gradlew test
```

> **Note:** Firebase Cloud Messaging (FCM) infrastructure is present in the code but intentionally disabled. The app works fully without `google-services.json`. If you want to enable FCM, see the "FCM Dormant State" section in [CLAUDE.md](CLAUDE.md).

### Self-Hosting the Relay Server

You can run your own relay server instead of using the default public server:

```bash
git clone https://github.com/clipboardpush/clipboard-push-server.git
cd clipboard-push-server
cp .env.example .env
# Edit .env with your settings
docker-compose up -d
```

Then in the Android app: **Settings → Server Address** → enter your server's address and port.

See the [relay server repository](https://github.com/clipboardpush/clipboard-push-server) for full deployment instructions.

### Privacy

- All clipboard text is encrypted with **AES-256-GCM** before leaving your device
- The relay server forwards encrypted ciphertext only — it cannot read your clipboard content
- Files transferred over LAN never touch the relay server
- No user accounts, no email, no personal data collected
- Full privacy policy: [clipboardpush.com/privacy](https://clipboardpush.com/privacy)

### Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.

Issues and feature requests: [GitHub Issues](https://github.com/clipboardpush/clipboard-push-android/issues)

### License

Licensed under the [Apache License 2.0](LICENSE).

---

## 中文说明

### 这是什么？

Clipboard Push 是一款 Android 剪贴板同步工具，能将手机和电脑的剪贴板内容实时同步。在手机上复制的内容会立刻出现在电脑上，反之亦然。无需注册账号，数据不上传云端。

**相关链接：**
- 网站：[clipboardpush.com](https://clipboardpush.com)
- PC 客户端（Windows）：[clipboard-push-win32](https://github.com/clipboardpush/clipboard-push-win32)
- 中继服务器：[clipboard-push-server](https://github.com/clipboardpush/clipboard-push-server)
- 隐私政策：[clipboardpush.com/privacy](https://clipboardpush.com/privacy)

### 功能特性

- **实时同步** — 复制即同步，无感知延迟
- **文件传输** — 从手机向电脑发送图片、文档、视频等文件
- **AES-256-GCM 加密** — 所有内容在离开设备前加密，中继服务器只能看到密文
- **局域网直传** — 同一 Wi-Fi 下文件直接在设备间传输，不经过云端，速度更快
- **扫码配对** — 扫描电脑客户端显示的二维码即可完成配对，无需注册
- **支持自托管** — 用 Docker 几分钟即可搭建私人中继服务器
- **开源** — 代码完全公开，可自行审计，无隐藏数据收集

### 快速开始

1. 从 [Google Play](https://play.google.com/store/apps/details?id=com.clipboardpush.plus) 安装 Android App，或从 [Releases](https://github.com/clipboardpush/clipboard-push-android/releases) 下载 APK
2. 下载并安装 [PC 客户端](https://github.com/clipboardpush/clipboard-push-win32/releases)（Windows）
3. 打开 Android App → 点击**连接** → 点击二维码图标 → 扫描 PC 客户端显示的二维码
4. 开始复制——内容自动同步

### 从源码构建

**环境要求：**
- Android Studio Hedgehog 或更新版本
- JDK 17
- Android SDK API 34

```bash
git clone https://github.com/clipboardpush/clipboard-push-android.git
cd clipboard-push-android

# 构建调试版 APK
./gradlew assembleDebug

# 运行单元测试（Robolectric，无需连接设备）
./gradlew test
```

### 自托管中继服务器

不想使用公共服务器？几分钟搭建私人服务器：

```bash
git clone https://github.com/clipboardpush/clipboard-push-server.git
cd clipboard-push-server
cp .env.example .env
# 编辑 .env 填入你的配置
docker-compose up -d
```

然后在 Android App 中：**设置 → 服务器地址** → 填入你的服务器地址和端口。

详细部署说明见[中继服务器仓库](https://github.com/clipboardpush/clipboard-push-server)。

### 隐私说明

- 所有剪贴板文本在发送前均经过 **AES-256-GCM** 加密
- 中继服务器只转发密文，无法解读内容
- 局域网直传的文件不经过中继服务器
- 无用户账号，无邮箱，不收集个人信息
- 完整隐私政策：[clipboardpush.com/privacy](https://clipboardpush.com/privacy)

### 贡献

欢迎贡献代码！提交 PR 前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。

问题反馈与功能建议：[GitHub Issues](https://github.com/clipboardpush/clipboard-push-android/issues)

### 许可证

本项目基于 [Apache 2.0 协议](LICENSE) 开源。
