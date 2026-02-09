# Clipboard Man - C++ Client (Win32)

This is a lightweight, pure C++ client for Clipboard Man, built using the Win32 API and modern C++ libraries. It does **not** require Qt.

## Features
- **Core**: Real-time clipboard synchronization (Text, Files).
- **Network**: Socket.IO for events, HTTP for file downloads.
- **Security**: AES-256-GCM End-to-End Encryption.
- **GUI**: System Tray icon, background operation, simple context menu.
- **Native**: Uses Windows API (`CF_HDROP`, `OpenClipboard`) for maximum compatibility.

## Build Instructions

### 1. Prerequisites
- **Visual Studio 2022** (Desktop C++ Workload).
- **CMake** 3.20+.
- **vcpkg**: Microsoft's C++ package manager.

### 2. Dependencies
Install the required libraries using vcpkg:
(Note: The `vcpkg.json` file in this directory handles this automatically when running CMake with the toolchain file).
- `cpr`
- `nlohmann-json`
- `spdlog`
- `websocketpp`
- `rapidjson`
- `asio`

### 3. Build Steps
Open PowerShell in the `cpp_client` directory:

```powershell
# Create build directory
mkdir build
cd build

# Configure (Replace path to your vcpkg toolchain)
cmake .. -DCMAKE_TOOLCHAIN_FILE=D:/vcpkg/scripts/buildsystems/vcpkg.cmake

# Build (Release mode)
cmake --build . --config Release
```

### 4. Running
The executable `clipboard-man-cpp.exe` will be located in `build/Release`.
- Ensure `config.json` is in the same directory (it is copied automatically).
- Run the executable. It will appear in the System Tray.

## Project Structure
- `src/main.cpp`: Entry point, Message Loop, Tray Icon.
- `src/NetworkClient.cpp`: Socket.IO and HTTP logic.
- `src/ClipboardManager.cpp`: Win32 Clipboard handling.
- `src/CryptoManager.cpp`: Encryption / Decryption.
- `src/ConfigManager.cpp`: Configuration loading.
