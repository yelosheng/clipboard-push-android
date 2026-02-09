# Findings: C++ GUI Client for Clipboard Man

## Python Client Analysis
- **Receiver (`clipboard_push_client.py`)**:
    - Uses `socket.io-client`.
    - Connects to `relay_server_url`.
    - Joins room with `room_id` and `client_id`.
    - Listens for `clipboard_sync`:
        - Payload: `{ "content": "...", "encrypted": true }`.
        - Decrypts content (Base64 -> AES-GCM -> Text).
        - Copies to clipboard.
    - Listens for `file_sync`:
        - Payload: `{ "download_url": "...", "filename": "...", "type": "file"|"image" }`.
        - Downloads file (stream).
        - Decrypts content in memory (or chunked).
        - Saves to `download_path` with unique name (counter).
        - If `image`: Sets `CF_DIB` to clipboard.
        - If `file`: Sets `CF_HDROP` to clipboard.

- **Sender (`push_to_clipboard_push_server.py`)**:
    - Uses HTTP `requests`.
    - Checks clipboard priority: Files > Image > Text.
    - **Files/Images**:
        - Encrypts file content.
        - Gets Upload URL via `POST /api/file/upload_auth`.
        - Uploads to R2 via `PUT`.
        - Sends `file_sync` event via `POST /api/relay`.
    - **Text**:
        - Encrypts text (Base64 context).
        - Sends `clipboard_sync` event via `POST /api/relay` (with `encrypted: true`).

- **Encryption (`crypto_utils.py`)**:
    - Algorithm: AES-256-GCM.
    - Key: 32 bytes (decoded from Base64 config).
    - IV/Nonce: 12 bytes (prepended to ciphertext).
    - Tag: 16 bytes (appended to ciphertext - standard GCM).

- **Configuration (`config.json`)**:
    - `relay_server_url`: Server URL.
    - `download_path`: Local save path.
    - `device_id`: Unique client ID.
    - `room_id`, `room_key`: Pairing info.

## Legacy C++ Code Analysis (`client2/clipboard-push`)
- **Technology**:
    - Build: CMake.
    - Network: `ixwebsocket` (WebSocket), `cpr` (HTTP).
    - JSON: `nlohmann/json`.
    - GUI: Raw Win32 API (`CreateWindowEx`, `WndProc`).
    - Logging: `spdlog`.
- **Clipboard (`Clipboard.cpp`)**:
    - `SetText`/`GetText`: Uses `OpenClipboard`, `SetClipboardData(CF_UNICODETEXT)`.
    - `SetFiles`/`GetFiles`: Uses `DROPFILES` struct with `CF_HDROP`. **Very useful reference**.
    - `SetImage`: Uses GDI+ to convert to BMP, but implementation seems partial or simplified. Qt supports this natively better (`QClipboard::setImage`).
- **Network (`Network.cpp`)**:
    - Manually handles Socket.IO protocol handshake (0 -> 40 -> 42...). **Weakness**: This is fragile. Using a proper library like `socket.io-client-cpp` is better.
    - Uses `cpr` for file downloads and uploads.

## C++ Technical Stack Strategy (Refined)
- **Framework**: **Qt 6 (Widgets)**.
    - *Why?* Legacy code uses raw Win32 which is hard to maintain and extend for complex UI (like Settings/QR). Qt provides a robust loop, signals/slots, and cross-platform structure.
    - **Network**: `socket.io-client-cpp` (v3+) for WebSocket (stable protocol handling). `QNetworkAccessManager` for HTTP (native integration with Qt loop).
    - **JSON**: `nlohmann/json`.
    - **Settings**: `QSettings` (INI format) or `nlohmann/json` to keep compatibility with Python's `config.json`. **Decision**: Use `nlohmann/json` to share the exact same `config.json`.
    - **Tray**: `QSystemTrayIcon`.
    - **Clipboard**: `QClipboard` for Text/Image. **Win32 API** for `CF_HDROP` (Qt doesn't officially support writing CF_HDROP easily across all versions, so using the logic from `Clipboard.cpp` for files is a good hybrid approach).
    - **Global Hotkeys**: `QHotkey` or Native `RegisterHotKey`.

## Implementation Modules
1.  **Core**: `ConfigManager` (JSON), `CryptoManager` (OpenSSL).
2.  **Network**: `SocketClient` (Socket.IO), `HttpClient` (Rest).
3.  **System**: `ClipboardManager` (Qt + Win32 Mix), `HotkeyManager`.
4.  **UI**: `MainWindow` (Dashboard), `SettingsDialog`, `PairingDialog` (QR).
