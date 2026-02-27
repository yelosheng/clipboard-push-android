# Clipboard Push — Android 版

> 在 Android 与电脑之间实时同步剪贴板，端对端 AES-256-GCM 加密。

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-purple.svg)](https://developer.android.com/jetpack/compose)

**相关链接：**
[官方网站](https://clipboardpush.com) · [PC 客户端](https://github.com/clipboardpush/clipboard-push-win32) · [中继服务器](https://github.com/clipboardpush/clipboard-push-server) · [隐私政策](https://clipboardpush.com/privacy)

---

## 这是什么？

Clipboard Push 是一款 Android 剪贴板同步工具，能将手机和电脑的剪贴板内容实时同步。在手机上复制的内容会立刻出现在电脑上，反之亦然。无需注册账号，数据不上传云端。

## 功能特性

- **实时同步** — 复制即同步，无感知延迟
- **文件传输** — 从手机向电脑发送图片、文档、视频等文件
- **AES-256-GCM 加密** — 所有内容在离开设备前加密，中继服务器只能看到密文
- **局域网直传** — 同一 Wi-Fi 下文件直接在设备间传输，不经过云端，速度更快
- **扫码配对** — 扫描电脑客户端显示的二维码即可完成配对，无需注册
- **支持自托管** — 用 Docker 几分钟即可搭建私人中继服务器
- **开源** — 代码完全公开，可自行审计，无隐藏数据收集

## 快速开始

1. 从 [Google Play](https://play.google.com/store/apps/details?id=com.clipboardpush.plus) 安装 App，或从 [Releases](https://github.com/clipboardpush/clipboard-push-android/releases) 下载 APK
2. 下载并安装 [PC 客户端](https://github.com/clipboardpush/clipboard-push-win32/releases)（Windows）
3. 打开 App → 点击**连接** → 点击二维码图标 → 扫描 PC 客户端显示的二维码
4. 开始复制——内容自动同步

## 架构

```
Android App  ── Socket.IO (AES-256-GCM 加密) ──► 中继服务器 ◄── Socket.IO (AES-256-GCM 加密) ──  PC 客户端
                                                       │
                                               云存储 R2（文件中转，可选）

同一 Wi-Fi 时：Android App ◄─── HTTP 直拉 ─── PC 客户端（跳过中继服务器）
```

- **文本同步**：Android 用 AES-256-GCM 加密后发至中继服务器，PC 客户端接收并解密
- **文件传输**：Android 广播文件可用，PC 优先尝试局域网直接拉取；失败时上传至云存储，PC 再下载
- **配对**：扫描 QR 码后，两端共享 room ID 和加密密钥，服务器全程不知道明文密钥

更多协议细节见 [CLAUDE.md](CLAUDE.md) 和 [LAN_SIGNAL_PROTOCOL.md](LAN_SIGNAL_PROTOCOL.md)。

## 从源码构建

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

> **注意：** 代码中保留了 Firebase Cloud Messaging (FCM) 基础设施，但已被有意禁用，不影响正常使用。无需 `google-services.json` 即可构建。如需了解重新启用步骤，见 [CLAUDE.md](CLAUDE.md) 中的"FCM Dormant State"节。

## 自托管中继服务器

不想使用公共服务器？几分钟搭建私人服务器：

```bash
git clone https://github.com/clipboardpush/clipboard-push-server.git
cd clipboard-push-server
cp .env.example .env
# 编辑 .env 填入你的配置
docker-compose up -d
```

然后在 App 中：**设置 → 服务器地址** → 填入你的服务器地址和端口。

详细部署说明见[中继服务器仓库](https://github.com/clipboardpush/clipboard-push-server)。

## 隐私说明

- 所有剪贴板文本在发送前均经过 **AES-256-GCM** 加密
- 中继服务器只转发密文，无法解读内容
- 局域网直传的文件不经过中继服务器
- 无用户账号，无邮箱，不收集个人信息
- 完整隐私政策：[clipboardpush.com/privacy](https://clipboardpush.com/privacy)

## 贡献

欢迎贡献代码！提交 PR 前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。

问题反馈与功能建议：[GitHub Issues](https://github.com/clipboardpush/clipboard-push-android/issues)

## 许可证

本项目基于 [Apache 2.0 协议](LICENSE) 开源。
