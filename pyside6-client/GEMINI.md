# Clipboard Push - Project Context

## Project Overview

**Clipboard Push** is a Windows desktop client for real-time clipboard synchronization between PC and Android devices. It supports synchronizing text, images, and files securely using end-to-end encryption.

This repository contains two implementations of the client:
1.  **Python Client (Root):** The current working prototype built with PySide6.
2.  **C++ Client (`cpp-client/`):** A native rewrite using Qt6 and C++17 for improved performance and native integration.

## Architecture & Shared Concepts

Both clients share the same architectural principles and configuration formats to ensure compatibility with the relay server and mobile clients.

### 1. Communication Protocol
*   **Socket.IO:** Used for real-time signaling and events (`connect`, `disconnect`, `clipboard_sync`, `file_sync`).
*   **HTTP REST:** Used for data transmission.
    *   Text is pushed via `POST /api/relay`.
    *   Files/Images are uploaded to R2-compatible storage via presigned URLs obtained from `/api/file/upload_auth`.

### 2. Security (End-to-End Encryption)
All clipboard content is encrypted client-side before transmission using **AES-256-GCM**.
*   **Key:** Derived from `room_key` (Base64 encoded 32-byte key).
*   **Format:** `[12-byte Nonce] + [Ciphertext] + [16-byte Tag]`.
*   **Note:** The C++ client must ensure strict byte-for-byte compatibility with this format to interoperate with Python clients.

### 3. Configuration (`config.json`)
Both clients utilize `config.json` for persistent settings:
*   `relay_server_url`: Address of the self-hosted relay server.
*   `room_id` & `room_key`: Credentials for the sync room.
*   `push_hotkey`: Global shortcut to trigger a push (e.g., "Ctrl+F6").
*   `device_id`: Unique identifier for the client.

## Python Client (PySide6)

Located in the root directory. This is the reference implementation.

### Setup & Run
```bash
# Create virtual environment
python -m venv venv
.\venv\Scripts\activate

# Install dependencies
pip install -r requirements.txt

# Run application
python main_pyside.py
```

### Key Files
*   `main_pyside.py`: Entry point. Manages the app lifecycle, configuration, and Win32 clipboard interactions.
*   `worker_threads.py`: Handles background tasks (Socket.IO networking, global hotkey listening).
*   `ui_manager.py`: Defines Qt widgets (`MainWindow`, `SettingsWindow`, `HotkeyRecorderEdit`).
*   `crypto_utils.py`: Wrapper for AES-GCM encryption/decryption.

## C++ Client (Qt6)

Located in `cpp-client/`. This is the high-performance native port.

### Prerequisites
*   Visual Studio 2022 (C++ workload).
*   CMake 3.21+.
*   vcpkg.

### Build & Run
```bash
cd cpp-client

# Install dependencies via vcpkg
vcpkg install qtbase[widgets,gui,network] openssl nlohmann-json spdlog cpr[ssl] socket-io-client nayuki-qr-code-generator --triplet x64-windows

# Configure (Debug)
cmake --preset x64-windows-debug

# Build
cmake --build build/x64-windows-debug

# Run
.\build\x64-windows-debug\ClipboardPush.exe
```

### Key Files
*   `src/main.cpp`: Entry point.
*   `src/core/CryptoManager.cpp`: AES-GCM implementation using OpenSSL.
*   `src/network/`: `SocketIOClient` and `HttpClient` implementations.
*   `src/platform/`: Windows-specific implementations (`ClipboardManager` using Win32 API, `HotkeyManager`).

## Development Notes

*   **Win32 API Usage:** Both clients rely on Windows API (`win32clipboard`, `win32con` in Python; `<windows.h>` in C++) for advanced clipboard operations like `CF_HDROP` (file paths) and `CF_DIB` (images).
*   **Porting Specification:** See `spec_for_cpp_rewrite.md` for detailed mapping of functionality from Python to C++.
