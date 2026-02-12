# C++ Porting Specification for Clipboard Push

This document serves as a detailed functional and technical specification for rewriting the "Clipboard Push" PySide6 client in C++ (Qt6).

## 1. Project Overview

**Application Name**: Clipboard Push
**Purpose**: Real-time clipboard synchronization (Text, Images, Files) between Windows PC and Android devices via a self-hosted Relay Server.
**Architecture**:
*   **Protocol**: Socket.IO (for signaling/events) + HTTP REST (for data transmission).
*   **Security**: End-to-End Encryption using AES-256-GCM.
*   **Platform**: Windows (Target), extensible to Linux/macOS.

## 2. Core Features & Requirements

### 2.1 Configuration (`config.json`)
The application must load/save settings from a JSON file.
*   **Fields**:
    *   `relay_server_url`: Server address (e.g., `http://kxkl.tk:5055`).
    *   `download_path`: Directory to save received files.
    *   `device_id`: Unique ID for the client (e.g., `pc_username_timestamp`).
    *   `room_id`: Collaboration room ID (auto-generated if missing).
    *   `room_key`: Base64 encoded AES-256 key (auto-generated if missing).
    *   `push_hotkey`: Global hotkey string (default: "Ctrl+F6").
    *   `auto_copy_image`: Boolean.
    *   `auto_copy_file`: Boolean.
    *   `auto_start`: Boolean.

### 2.2 Network Layer
*   **Socket.IO Client**:
    *   **Events to Listen**:
        *   `connect`: Send `join` event with `{"room": "...", "client_id": "..."}`.
        *   `disconnect`: Handle reconnection logic.
        *   `clipboard_sync`: Receives JSON `{"content": "..."}`. Payload is Encrypted Base64.
        *   `file_sync`: Receives JSON `{"download_url": "...", "filename": "...", "type": "..."}`.
*   **HTTP REST**:
    *   **Push Text**: `POST /api/relay` (Payload includes encrypted Base64 content).
    *   **Push File**:
        1.  `POST /api/file/upload_auth` (Get upload URL).
        2.  `PUT <upload_url>` (Upload encrypted binary data).
        3.  `POST /api/relay` (Notify room with download URL).

### 2.3 Encryption (Critical)
*   **Algorithm**: AES-256-GCM.
*   **Key**: 32 bytes (decoded from Base64 `room_key`).
*   **IV/Nonce**: 12 bytes (Generated random for encrypt, read from prefix for decrypt).
*   **Tag**: 16 bytes (Appended to ciphertext).
*   **Data Format**: `[Nonce (12B)] + [Ciphertext] + [Tag (16B)]`.
    *   *Note*: Some libraries append Tag automatically, others don't. Ensure format compatibility with Python `cryptography.hazmat.primitives.ciphers.aead.AESGCM`.

### 2.4 Clipboard Management
The C++ implementation must handle Windows-specific clipboard formats to ensure seamless integration with Explorer.
*   **Text**: Standard UTF-8 text.
*   **Images**:
    *   **Read**: Capture clipboard image -> Convert to PNG bytes -> Encrypt -> Upload.
    *   **Write**: Decrypt -> Save as temp BMP/PNG -> Write to Clipboard as `CF_DIB` (Device Independent Bitmap).
*   **Files**:
    *   **Read**: Get file paths from `CF_HDROP`.
    *   **Write**: Receive file -> Decrypt -> Save to `download_path` -> Write path to Clipboard as `CF_HDROP`.

### 2.5 Global Hotkey
*   **Function**: Trigger "Push" action from background.
*   **Implementation**:
    *   Must support combinations like `Ctrl+F6`, `Alt+V`.
    *   **Recommendation**: Use `RegisterHotKey` (Win32 API) or a Qt-native global shortcut library (e.g., `QHotKey`). The Python version uses `pynput` listener for reliability, but C++ `RegisterHotKey` is standard and reliable on Windows.

## 3. UI Specification (Qt6)

### 3.1 Main Window (`MainWindow`)
A lightweight, clean interface for everyday use.
*   **Components**:
    *   **Text Area** (`QTextEdit`): Plain text input. Placeholder: "Enter text to push... (Ctrl+Enter to send)".
    *   **Buttons**:
        *   `Push` (Primary): Encrypts and pushes text from the text area.
        *   `Settings` (Icon `⚙`): Opens the Settings Window.
    *   **Status Label**: Shows "Ready", "Success", or "Error".
*   **Behavior**:
    *   `Ctrl+Enter` in text area triggers Push.
    *   Clicking "Settings" opens the config dialog.
    *   Closing this window should probably minimize to tray instead of quitting (user preference).

### 3.2 Settings Window (`SettingsWindow`)
A comprehensive configuration dialog.
*   **Fields**:
    *   `Server URL` (Hidden/ReadOnly): Read from config.
    *   `Download Path`: `QLineEdit` + `QPushButton` ("...") to open Folder Picker.
    *   `Push Hotkey`: Custom Input Widget.
        *   **Behavior**: Click to focus -> Press keys -> Captures combination (e.g., "Ctrl+Alt+A") -> Click elsewhere to save.
    *   `Checkboxes`: Auto Copy Images, Auto Copy Files, Start on Boot.
*   **QR Code**:
    *   Displays pairing info: `clipboard-man://pair?room=...&key=...&server=...`.
    *   Generated locally using a C++ QR library (e.g., `QRCodegen` or `Nayuki`).
*   **Actions**:
    *   `Save Settings`: Saves to `config.json` -> Reconnects network -> Restarts Hotkey.
    *   `Push Manual`: Triggers "Push Clipboard" logic (same as Hotkey).
    *   `Reconnect`: Force network reconnect.
    *   `Close`: Hides window.

### 3.3 System Tray
*   **Icon**: Application Logo.
*   **Context Menu**:
    1.  `Push Clipboard` (Triggers sync).
    2.  `Open Main Window`.
    3.  `Settings`.
    4.  `Quit` (Fully terminates app).
*   **Interaction**: Left-click / Double-click -> Open Main Window.

## 4. Implementation Recommendations (C++)

| Component | Recommended Library / API | Notes |
| :--- | :--- | :--- |
| **Framework** | **Qt 6** (Widgets) | Industry standard, matches PySide6 structure. |
| **Network (SIO)** | `socket.io-client-cpp` | Official C++ client. |
| **Network (HTTP)** | `QNetworkAccessManager` | Native Qt HTTP handling. |
| **JSON** | `QJsonDocument` or `nlohmann/json` | `nlohmann` is often friendlier. |
| **Crypto** | `OpenSSL` (libcrypto) | For AES-GCM. Do not roll your own crypto. |
| **Windows API** | `<windows.h>`, `<shlobj.h>` | Required for `CF_HDROP` (File copy/paste) and `RegisterHotKey`. |
| **QR Code** | `nayuki/QR-Code-generator` | Lightweight, header-only C++ library. |
| **Logging** | `spdlog` or `QDebug` | `spdlog` for file/console logging similar to `loguru`. |

## 5. Logic Flow Details

### 5.1 Push Logic (Hotkey / Manual)
1.  **Check Payload Type**:
    *   Is it a File list? (`CF_HDROP`) -> **Push File(s)**.
    *   Is it an Image? (`CF_DIB`) -> **Push Image**.
    *   Is it Text? (`CF_TEXT`/Unicode) -> **Push Text**.
2.  **Push Text**:
    *   Read String.
    *   Encrypt `AES-GCM(bytes(String))`.
    *   Base64 Encode.
    *   POST JSON to `/api/relay`.
3.  **Push File/Image**:
    *   Read Binary Data.
    *   Encrypt `AES-GCM(Data)`.
    *   POST `/api/file/upload_auth` -> Get `upload_url`.
    *   PUT `upload_url` with Encrypted Data.
    *   POST `/api/relay` with metadata (`download_url`, `filename`, `iv`, etc.).

### 5.2 Receive Logic
1.  **On `clipboard_sync` (Text)**:
    *   Decode Base64 -> Decrypt AES-GCM -> String.
    *   `QClipboard::setText(String)`.
    *   Show Tray Notification.
2.  **On `file_sync` (File/Image)**:
    *   GET `download_url` -> Download Encrypted Bytes.
    *   Decrypt AES-GCM -> Save to Disk (`download_path`).
    *   **If Image & Auto-Copy**: Load Image -> `QClipboard::setImage`.
    *   **If File & Auto-Copy**: Create `DropData` (CF_HDROP) -> Set to Clipboard.
    *   Show Tray Notification.

## 6. Development Checklist
- [ ] Setup C++ Project (CMake/qmake).
- [ ] Integrate Dependencies (Qt6, SocketIO, OpenSSL).
- [ ] Implement `CryptoManager` (AES-GCM wrapper).
- [ ] Implement `NetworkManager` (SocketIO + HTTP).
- [ ] Implement `ClipboardManager` (Win32 API wrapper for advanced formats).
- [ ] Implement `HotkeyManager` (Global shortcut).
- [ ] Build Main Window & Settings UI.
- [ ] Wiring & Testing.
