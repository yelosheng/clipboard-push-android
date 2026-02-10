# Task Plan: Clipboard Push v3.0 (C++/Win32)

**Goal**: Create an ultra-lightweight (<1MB), high-performance Windows client for Clipboard Push using pure C++ and Win32 API.
**Constraint**: Must match functionality and UI of the v2.0 (Qt) and v1.0 (Python) clients.

## Phase 1: Architecture & Setup
- [x] **Project Initialization**: Setup CMake project structure.
- [x] **Dependency Management**: Integrate `nlohmann/json` (single header).
- [x] **Build Setup**: Configure MSVC compiler flags for minimal size (`/O1`, `/Os`, no RTTI/Exceptions if possible).

## Phase 2: Core Foundation
- [x] **Logger**: Simple file/console logger (replace spdlog to save size).
- [x] **Config**: JSON configuration loader/saver (compat with v2.0 `config.json`).
- [x] **Utils**: String conversions (Wide/UTF-8), Path helpers.

## Phase 3: Cryptography & Network
- [x] **Crypto**: Implement AES-256-GCM using **Windows CNG (BCrypt)**.
    - [x] Key Derivation / Decoding.
    - [x] Encrypt/Decrypt logic.
- [x] **Network**: Implement HTTP/WebSocket using **WinHTTP**.
    - [x] REST Client (POST text, GET/PUT files).
    - [x] WebSocket Client (Socket.IO protocol handshake).

## Phase 4: Platform Integration
- [x] **Clipboard**: Native Win32 Clipboard handling (`OpenClipboard`, `GetClipboardData`).
    - [x] Text (CF_UNICODETEXT).
    - [x] Files (CF_HDROP).
    - [x] Images (CF_DIB) - *Using GDI+ for PNG conversion.*
- [x] **Hotkey**: `RegisterHotKey` implementation.
- [x] **Tray Icon**: `Shell_NotifyIcon` implementation.

## Phase 5: UI Implementation
- [x] **Resource Script**: Define Dialogs (Main, Settings) and Menus in `.rc` file.
- [x] **Main Window**: DialogProc implementation.
- [x] **Settings Window**: DialogProc implementation.
- [x] **QR Code**: Integrate `nayuki-qr-code-generator` (cpp) and Paint with GDI.

## Phase 6: Logic Integration
- [ ] **Event Loop**: Custom message loop handling UI and Network events.
- [ ] **Sync Logic**: Connect Clipboard changes to Network push.
- [ ] **Receive Logic**: Handle WebSocket events -> System Clipboard.

## Phase 7: Optimization & Release
- [ ] **Size Optimization**: Strip symbols, merge sections, UPX.
- [ ] **Icon**: Embed Application Icon.
- [ ] **Testing**: Verify against Relay Server.