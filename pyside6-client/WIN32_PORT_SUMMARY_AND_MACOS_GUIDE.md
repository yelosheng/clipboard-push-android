# Win32 Porting Summary & macOS Development Guide

## 1. Project Overview (v3.0 Win32 Edition)
We successfully ported the Clipboard Push client from a 79MB Qt/Python dependency-heavy application to a **336KB single-file executable** using pure C++17 and Win32 APIs.

**Key Achievements:**
- **Zero External DLLs**: Fully reliant on system libraries (`kernel32`, `user32`, `gdi32`, `winhttp`, `bcrypt`).
- **Protocol Fidelity**: Full support for Socket.IO 4.0, AES-256-GCM encryption, and custom file synchronization protocols.
- **Modern UI**: Native Windows controls with V6 visual styles, tooltips, and custom GDI rendering.
- **High DPI**: Full 4K clarity via hardware-aware rendering.

---

## 2. API Mapping Guide (Win32 -> macOS)

This section maps the key Win32 APIs used in v3.0 to their equivalents in the macOS (Cocoa/Foundation) ecosystem for future development.

| Component | Win32 API (v3.0) | macOS Equivalent (Future) | Notes |
| :--- | :--- | :--- | :--- |
| **Entry Point** | `wWinMain` / `main` | `NSApplicationMain` / `main` | macOS apps are bundles (`.app`), requiring `Info.plist`. |
| **Networking** | `WinHTTP` (`winhttp.dll`) | `NSURLSession` (Foundation) | `NSURLSession` natively supports HTTP/2 and WebSockets (`NSURLSessionWebSocketTask`). |
| **Cryptography** | `CNG` (`bcrypt.dll`) | `CryptoKit` (Swift/ObjC) or `CommonCrypto` (C) | `CryptoKit.AES.GCM` is the modern, preferred API on macOS. |
| **Clipboard** | `OpenClipboard`, `GetClipboardData` | `NSPasteboard` (AppKit) | Use `NSPasteboard.general`. Map `CF_TEXT` to `NSPasteboardTypeString`. |
| **High DPI** | `SetProcessDPIAware()` | Automatic (Info.plist) | macOS handles Retina display scaling automatically if defined in the app bundle. |
| **Global Hotkey** | `RegisterHotKey` | `CGEventTap` or `MASShortcut` | Requires "Accessibility" permissions on modern macOS. |
| **System Tray** | `Shell_NotifyIcon` | `NSStatusItem` (AppKit) | The "Menu Bar App" pattern is standard on macOS. |
| **UI Framework** | Native Dialogs (`CreateDialogParam`) | SwiftUI or AppKit (XIB/Storyboard) | SwiftUI is recommended for modern, declarative UI code. |
| **Settings** | Registry (`RegSetValueEx`) | `UserDefaults` (`plist`) | `UserDefaults.standard` is the standard key-value store. |

---

## 3. Critical Technical Lessons (Troubleshooting & Fixes)

### A. Networking & WebSocket Stability
- **Protocol Mapping**: Win32's `WinHttpCrackUrl` does not recognize `ws://` or `wss://`. You must temporarily map them to `http` or `https` for parsing, then set the `WINHTTP_OPTION_UPGRADE_TO_WEB_SOCKET` option.
- **TLS Version**: Modern servers (R2/S3) require TLS 1.2 or 1.3. WinHTTP may default to older versions; explicitly set `WINHTTP_OPTION_SECURE_PROTOCOLS`.
- **Zombie Connections**: Connections can "hang" without triggering a disconnect. Implementation of a **Watchdog timer** (monitoring heartbeats) and setting `WINHTTP_OPTION_RECEIVE_TIMEOUT` is mandatory for 24/7 reliability.
- **Release Threading**: In Release mode, a tight network receive loop can hog the CPU or starve other threads. A tiny `std::this_thread::sleep_for(1ms)` ensures the OS can schedule the transmission thread (needed for replying to Pings).

### B. Unicode & Filename Support
- **Encoding Pitfall**: Windows uses UTF-16 (`wstring`) for file paths. Passing UTF-8 strings to `std::ifstream` or `fs::path` will fail or corrupt Chinese/Unicode filenames.
- **Resolution**: Convert all incoming UTF-8 data to `std::wstring` before interacting with the Windows File System.
- **Presigned URLs**: S3/R2 signatures are extremely sensitive to `Content-Type`. Ensure the upload request's `Content-Type` matches exactly what was requested in the `upload_auth` phase.

### C. UI & Visual Clarity
- **Visual Styles**: To avoid the "Windows 95 look," you must link to Common Controls v6 via a linker pragma or manifest.
- **High DPI**: Without `SetProcessDPIAware()`, Win32 apps will be blurry on 4K screens due to bitmap stretching.
- **Clipboard Images**: Use **`CF_DIB`** instead of `CF_BITMAP`. Most modern apps (browsers, Telegram) fail to recognize `CF_BITMAP` and will show an empty placeholder.

### D. Thread Safety
- **std::thread lifecycle**: Overwriting a `joinable` thread object (e.g., during reconnection) triggers `abort()`. Always check `joinable()` and call `detach()` or `join()` before reassigning a thread object.

---

## 4. Cross-Platform Compatibility Strategy

### A. Core Logic Isolation (Portable C++)
The following modules in `src/core/` are platform-independent and can be reused 100%:
- **`Config.cpp`**: Logic for JSON parsing (`nlohmann/json`).
- **`SocketIOService.cpp`**: The protocol logic (handshake 40/42, ping/pong).
- **`Crypto.cpp`** (Wrapper): Keep the interface consistent across platforms.

### B. Security Strategy
- **AES-256-GCM**: Always use the native system provider (CNG on Windows, CryptoKit on macOS) to ensure hardware acceleration and FIPS compliance without carrying OpenSSL.

---

## 5. Recommended macOS Stack
- **Language**: Objective-C++ (`.mm`) to bridge C++ logic with Cocoa.
- **Build System**: CMake (generating Xcode project).
- **UI**: SwiftUI for a modern, responsive look with minimal boilerplate.

*Generated by Gemini CLI - v3.0 Finalized Documentation*