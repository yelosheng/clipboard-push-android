# Clipboard Push - C++ Client

Native Windows desktop client for real-time clipboard synchronization, built with Qt6 and C++17.

## Prerequisites

- Visual Studio 2022 (with C++ workload)
- CMake 3.21+
- vcpkg (with VCPKG_ROOT environment variable set)
- Ninja (recommended)

## Dependencies

All dependencies are managed via vcpkg:

- Qt6 (widgets, gui, network)
- OpenSSL
- nlohmann-json
- spdlog
- cpr
- socket-io-client
- nayuki-qr-code-generator

## Build Instructions

### 1. Install vcpkg dependencies

```bash
vcpkg install qtbase[widgets,gui,network] openssl nlohmann-json spdlog cpr[ssl] socket-io-client nayuki-qr-code-generator --triplet x64-windows
```

### 2. Configure the project

```bash
cd D:\android-dev\clipboard-man\pyside6-client\cpp-client
cmake --preset x64-windows-debug
```

### 3. Build

```bash
cmake --build build/x64-windows-debug
```

### 4. Run

```bash
.\build\x64-windows-debug\ClipboardPush.exe
```

## Features

- **Real-time clipboard sync** via Socket.IO
- **End-to-end encryption** using AES-256-GCM
- **Multi-format support**: Text, files, and images
- **Global hotkey** (default: Ctrl+F6)
- **System tray integration**
- **QR code pairing** for mobile devices

## Configuration

Settings are stored in `config.json` next to the executable:

```json
{
    "relay_server_url": "http://your-server:5055",
    "download_path": "C:\\Users\\...\\Downloads\\ClipboardMan",
    "device_id": "pc_user_1234",
    "room_id": "room_1234567890",
    "room_key": "base64-encoded-256-bit-key",
    "push_hotkey": "Ctrl+F6",
    "auto_copy_image": true,
    "auto_copy_file": true,
    "auto_start": false
}
```

## Encryption Format

Compatible with the Python client using `cryptography.hazmat.primitives.ciphers.aead.AESGCM`:

```
[12-byte nonce] + [ciphertext] + [16-byte tag]
```

## Project Structure

```
cpp-client/
├── CMakeLists.txt
├── CMakePresets.json
├── vcpkg.json
├── src/
│   ├── main.cpp
│   ├── Application.h/cpp
│   ├── core/
│   │   ├── Config.h/cpp
│   │   ├── CryptoManager.h/cpp
│   │   └── Logger.h
│   ├── network/
│   │   ├── SocketIOClient.h/cpp
│   │   └── HttpClient.h/cpp
│   ├── platform/
│   │   ├── ClipboardManager.h/cpp
│   │   └── HotkeyManager.h/cpp
│   └── ui/
│       ├── MainWindow.h/cpp
│       ├── SettingsWindow.h/cpp
│       ├── TrayIcon.h/cpp
│       └── widgets/
│           ├── HotkeyRecorderEdit.h/cpp
│           └── QRCodeWidget.h/cpp
└── resources/
    ├── resources.qrc
    └── icon.png
```
