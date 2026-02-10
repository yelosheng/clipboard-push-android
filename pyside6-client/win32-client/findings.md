# Findings & Tech Stack

## Technology Selection (Win32 "Ultra-Light" Edition)

### 1. Networking: WinHTTP
- **Why**: Built-in Windows API, supports HTTP/1.1 and WebSockets (Windows 8+).
- **Advantage**: Zero external dependencies (unlike libcurl or QtNetwork).
- **Note**: Need to handle the WebSocket handshake and framing manually or use WinHTTP WebSocket API.

### 2. Cryptography: Windows CNG (Cryptography API: Next Generation)
- **Why**: Built-in, FIPS compliant, hardware accelerated.
- **Advantage**: Replaces OpenSSL (which is huge).
- **Target**: `BCryptEncrypt`, `BCryptDecrypt` with `BCRYPT_AES_GCM_CHAIN_MODE`.

### 3. JSON Parsing: nlohmann/json
- **Why**: Industry standard, easy to use.
- **Cost**: Header-only, might add compile time/binary size.
- **Alternative**: If size is critical, write a tiny custom parser (only need minimal features). *Decision: Start with nlohmann, optimize later if needed.*

### 4. UI Framework: Pure Win32 Dialogs
- **Why**: Minimal overhead. Windows draws it.
- **Method**: Use Resource Editor (or manual `.rc` file) to define `IDD_MAINWINDOW`, `IDD_SETTINGSWINDOW`.
- **Image Handling**: **GDI+** (gdiplus.dll) for saving Clipboard DIB to PNG (required for syncing images to server). GDI+ is standard on XP+.

### 5. Build System
- **CMake**: Standard, integrates with VS.
- **Flags**:
    - `/O1` (Minimize Size)
    - `/GS-` (Disable buffer security check - optional, for extreme size)
    - Statically link CRT (`/MT`) to avoid VC Runtime dependency? -> Increases size but portable. *Decision: Use `/MD` (Dynamic) initially to keep exe small, rely on system installed runtimes, or `/MT` for "true" portability.*

### 6. Project Structure
- Single executable.
- Static linking for `nlohmann` and `qr-code`.
- Dynamic linking for System DLLs (`kernel32`, `user32`, `gdi32`, `winhttp`, `bcrypt`, `gdiplus`, `shell32`).
