# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Development Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Install debug APK to connected device
./gradlew installDebug

# Run unit tests
./gradlew test

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Run a single test class
./gradlew test --tests "com.example.clipboardman.YourTestClass"

# Clean build
./gradlew clean

# Check for lint issues
./gradlew lint
```

## Project Overview

This is an **Android clipboard synchronization app** (Kotlin + Jetpack Compose) that syncs clipboard content between Android and a PC client over LAN or via a relay server. The app ID is `com.example.clipboardpush.plus`, targeting Android 8.0+ (API 26+).

## Architecture

### Communication Architecture

The app supports two sync modes:

1. **Relay Server (Socket.IO)**: Clipboard text/files are exchanged through a Python relay server. The Android client connects via Socket.IO using `RelayRepository`. Text is AES-GCM encrypted before sending.

2. **LAN Direct Transfer ("Announce & Pull")**: For files, the PC announces availability via `file_available` Signal (see `LAN_SIGNAL_PROTOCOL.md`). Android attempts a direct LAN HTTP download; if it fails, it sends `file_need_relay` to trigger a cloud upload fallback. See `LAN_SYNC_DEV_PLAN.md` for full state machine.

### Socket.IO Protocol

- Join event sends `room`, `client_id`, `client_type`, `device_name`, `network info`, `protocol_version` (V4)
- Canonical LAN file events: `file_available` → `file_sync_completed` or `file_need_relay`
- Legacy aliases exist (`file_announcement`, `file_ack`, `file_request_relay`) — receive only during transition

### Layer Structure

```
Activities/UI (Compose screens)
    └── MainViewModel (StateFlow-based UI state)
            ├── SettingsRepository (DataStore preferences)
            └── MessageRepository (message history)

ClipboardService (foreground service — owns the connection lifecycle)
    ├── RelayRepository (Socket.IO client, emits RelayEvent sealed class)
    ├── ApiService (OkHttp HTTP client for REST calls)
    ├── LocalFileServer (NanoHTTPD, serves files for LAN pull)
    └── WorkManager workers:
            ├── UploadWorker (cloud file upload)
            └── DownloadWorker (cloud file download)
```

`ClipboardService` is the central coordinator. `MainActivity` binds to it via `LocalBinder` to expose service state to the UI. `MainViewModel` reads state from the service (polled/pushed) and exposes it as `StateFlow` for Compose.

### Key Files

| File | Purpose |
|------|---------|
| `service/ClipboardService.kt` | Foreground service; owns Socket.IO lifecycle, clipboard monitoring, file transfer orchestration |
| `data/repository/RelayRepository.kt` | Socket.IO client; emits `RelayEvent` sealed class to service |
| `data/repository/SettingsRepository.kt` | DataStore-backed settings (server URL, room ID, encryption key, feature flags) |
| `data/repository/MessageRepository.kt` | Persists and exposes clipboard message history |
| `ui/viewmodel/MainViewModel.kt` | UI state (connection, peers, messages) as `StateFlow` |
| `util/CryptoManager.kt` | AES-GCM encrypt/decrypt for clipboard content |
| `service/LocalFileServer.kt` | NanoHTTPD server for LAN file serving |
| `share/ShareReceiverActivity.kt` | Handles incoming `ACTION_SEND` intents (text, image, file) |

### Notification Channels

Defined in `ClipboardManApp.kt`:
- `clipboard_service` — LOW priority, silent (used by the foreground service notification)
- `clipboard_push` — HIGH priority with sound/vibration (used for received content)

### Peer Count Guard

`ClipboardService` skips sending clipboard content when `activePeerCount == 0` to avoid pointless relay traffic. Any UI "push" action should respect `MainViewModel.hasPeers`.
