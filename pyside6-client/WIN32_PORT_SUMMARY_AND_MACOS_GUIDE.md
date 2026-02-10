# Win32 Porting Summary & macOS Development Guide

## 1. Project Overview (v3.0 Win32 Edition)
We successfully ported the Clipboard Push client from a 79MB Qt/Python dependency-heavy application to a **336KB single-file executable** using pure C++17 and Win32 APIs.

**Key Achievements:**
- **Zero External DLLs**: Fully reliant on system libraries (`kernel32`, `user32`, `gdi32`, `winhttp`, `bcrypt`).
- **Protocol Fidelity**: Full support for Socket.IO 4.0, AES-256-GCM encryption, and custom file synchronization protocols.
- **Modern UI**: Native Windows controls with V6 visual styles, tooltips, and custom GDI rendering.

---

## 2. API Mapping Guide (Win32 -> macOS)

This section maps the key Win32 APIs used in v3.0 to their equivalents in the macOS (Cocoa/Foundation) ecosystem for future development.

| Component | Win32 API (v3.0) | macOS Equivalent (Future) | Notes |
| :--- | :--- | :--- | :--- |
| **Entry Point** | `wWinMain` / `main` | `NSApplicationMain` / `main` | macOS apps are bundles (`.app`), requiring `Info.plist`. |
| **Networking** | `WinHTTP` (`winhttp.dll`) | `NSURLSession` (Foundation) | `NSURLSession` natively supports HTTP/2 and WebSockets (`NSURLSessionWebSocketTask`). |
| **Cryptography** | `CNG` (`bcrypt.dll`) | `CryptoKit` (Swift/ObjC) or `CommonCrypto` (C) | `CryptoKit.AES.GCM` is the modern, preferred API on macOS. |
| **Clipboard** | `OpenClipboard`, `GetClipboardData` | `NSPasteboard` (AppKit) | Use `NSPasteboard.general`. Map `CF_TEXT` to `NSPasteboardTypeString`. |
| **Global Hotkey** | `RegisterHotKey` | `CGEventTap` or `MASShortcut` | Requires "Accessibility" permissions on modern macOS. |
| **System Tray** | `Shell_NotifyIcon` | `NSStatusItem` (AppKit) | The "Menu Bar App" pattern is standard on macOS. |
| **UI Framework** | Native Dialogs (`CreateDialogParam`) | SwiftUI or AppKit (XIB/Storyboard) | SwiftUI is recommended for modern, declarative UI code. |
| **File I/O** | `CreateFile`, `std::ofstream` | `FileManager` / `std::ofstream` | Use standard POSIX/C++ APIs or Foundation's `FileManager`. |
| **Settings** | Registry (`RegSetValueEx`) | `UserDefaults` (`plist`) | `UserDefaults.standard` is the standard key-value store. |

---

## 3. Cross-Platform Compatibility Strategy

To maintain a unified codebase logic while supporting platform-specific implementations:

### A. Core Logic Isolation (Portable C++)
The following modules in `src/core/` are platform-independent and can be reused 100%:
- **`Config.cpp`**: Logic for JSON parsing (`nlohmann/json`) is portable. Only the storage path retrieval needs per-platform `#ifdef`.
- **`SocketIOService.cpp`**: The protocol logic (handshake 40/42, ping/pong) is generic. The underlying transport (`m_ws`) should be an interface.
- **`Crypto.cpp`** (Wrapper): The *interface* (`Encrypt`, `Decrypt`) is portable. The *implementation* needs `#ifdef __APPLE__` to call CryptoKit/CommonCrypto instead of BCrypt.

### B. Interface Abstraction
We used static classes like `Clipboard::Get()` and `HttpClient::Post()`. For macOS, we should formalize this:
```cpp
// IClipboard.h
class IClipboard {
    virtual void SetText(std::string text) = 0;
};

// ClipboardMac.mm (Objective-C++)
class MacClipboard : public IClipboard { ... uses NSPasteboard ... }
```

---

## 4. Performance Optimization Lessons

### Win32 Insights
- **Message Loop Efficiency**: Using `AddClipboardFormatListener` (Event-driven) instead of polling saved significant CPU. On macOS, use `NSPasteboard.changeCount` observing or polling with a low-frequency timer (since macOS doesn't have a direct global clipboard event without Accessibility hooks).
- **Binary Size**: Removing `iostream` and RTTI (`/GR-`) where possible helped. On macOS, stripping symbols (`strip`) and using Link-Time Optimization (LTO) in Xcode/Clang is crucial.
- **Network**: `WinHTTP` is lightweight but synchronous-friendly. `NSURLSession` is inherently asynchronous (delegate/block-based). The macOS port will likely be more naturally async.

---

## 5. Security Strategy

### AES-256-GCM
- **Win32**: Used `BCrypt` with `BCRYPT_CHAIN_MODE_GCM`.
- **macOS**: Must use **AES-GCM** explicitly. Do not fallback to ECB/CBC. `CommonCrypto` (CC) is older; prefer **CryptoKit** (requires bridging Swift to C++) or raw `CCryptor` with GCM if available in newer SDKs.

### Presigned URLs (S3/R2)
- **Lesson**: S3 signatures are strictly tied to Headers and Query Params.
- **Action**: Ensure the macOS `NSURLRequest` does **not** automatically add `Content-Type` headers that differ from the signature (e.g., `application/octet-stream`).

### Sandbox & Permissions
- **Windows**: Relatively open.
- **macOS**: Highly restricted.
    - **App Sandbox**: If enabled, file access is restricted to `~/Downloads` or user-selected folders.
    - **Input Monitoring**: Global hotkeys require explicit user permission in System Settings -> Privacy.
    - **Network**: Requires "Outgoing Connections (Client)" entitlement.

---

## 6. Recommended macOS Stack
- **Language**: Objective-C++ (`.mm`) mixed with standard C++. This allows direct use of existing C++ logic while calling Cocoa APIs.
- **Build System**: CMake (generating Xcode project) or native Xcode.
- **UI**: SwiftUI (embedded in `NSHostingController`) for a tiny, modern UI, or pure AppKit for minimal size.

*Generated by Gemini CLI - v3.0 Post-Mortem*
