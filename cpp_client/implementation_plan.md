# Implementation Plan - C++ GUI Client

This plan outlines the development of the C++ GUI Client for Clipboard Man, replicating and enhancing the functionality of the existing Python client.

## User Review Required
> [!IMPORTANT]
> **Encryption Compatibility**: The C++ client MUST use OpenSSL to match Python's `cryptography` AES-GCM format.
> **Dependencies**: I will use **Qt 6** for GUI/Network and **vcpkg** or **CMake FetchContent** for dependencies (`socket.io-client-cpp`, `nlohmann-json`, `openssl`).
> **Clipboard Strategy**: I will use a **Hybrid Approach**. Qt for UI and simple clipboard text/image. Native Win32 API (adapted from `client2/clipboard-push/src/Clipboard.cpp`) for robust file list (`CF_HDROP`) support.

## Proposed Changes

### Project Structure (New Directory `cpp_client`)
- `CMakeLists.txt`: Build configuration.
- `src/`: Source code.
- `include/`: Header files.
- `resources/`: Icons, UI files.

### 1. Core Logic [Logic Layer]
#### [NEW] `src/ConfigManager.cpp/h`
- Load/Save `config.json` using `nlohmann/json` to match Python format.
- Manage `device_id` generation.

#### [NEW] `src/CryptoManager.cpp/h`
- Wraps OpenSSL `EVP_aes_256_gcm`.
- `encrypt(data)`: Returns `nonce + ciphertext + tag`.
- `decrypt(data)`: Parses `nonce (12) + ciphertext + tag (16)`.
- `encryptFile(path)`, `decryptFile(path)`.

### 2. Network Layer
#### [NEW] `src/NetworkClient.cpp/h`
- Wraps `sio::client` (Socket.IO).
- Handles `connect`, `disconnect`, `join` events.
- Listens for `clipboard_sync`, `file_sync`.
- Uses `QNetworkAccessManager` for HTTP uploads (Sender logic).

### 3. System Integration
#### [NEW] `src/ClipboardManager.cpp/h`
- `setText(QString)`: Uses `QClipboard`.
- `setFiles(QStringList)`: **Uses Win32 `CF_HDROP` logic adapted from legacy code.**
- `setImage(QImage/Data)`: Uses Win32 `CF_DIB` (Raw) or `QClipboard`.
- `monitor()`: Listens for system clipboard changes (Sender logic).

#### [NEW] `src/HotkeyManager.cpp/h`
- Uses native `RegisterHotKey` (Windows) to listen for global send shortcut.

### 4. User Interface [Qt Widgets]
#### [NEW] `src/MainWindow.cpp/h`
- **Dashboard**: Connection status, Last sync info.
- **Log View**: Rich text log of activities.

#### [NEW] `src/SettingsDialog.cpp/h`
- Server URL input.
- Save Path selector.
- Shortcut recorder.

#### [NEW] `src/PairingDialog.cpp/h`
- "Generate New Pairing": Creates new RoomID/Key, displays QR Code (using `qrcodegen`).
- "Manual Input": Text fields for RoomID/Key.

## Verification Plan

### Automated Tests
- **Unit Tests (`tests/`)**:
    - `CryptoTest`: Verify Python compatibility. Encrypt in Python, Decrypt in C++, and vice versa.
    - `ConfigTest`: Save/Load JSON.

### Manual Verification
1.  **Pairing**:
    - Build and run C++ client.
    - Click "Pairing" -> "Generate".
    - Scan QR with Android App.
    - Verify "Joined Room" status on both.
2.  **Text Sync**:
    - Copy text on Android.
    - Check if C++ client clipboard updates.
    - Copy text on PC, press Global Hotkey.
    - Check if Android receives it.
3.  **Image/File Sync**:
    - Send image from Android.
    - Verify C++ downloads, decrypts, and sets to clipboard.
    - Paste in Paint/Explorer.
4.  **Persistence**:
    - Restart C++ client.
    - Verify it auto-reconnects.
