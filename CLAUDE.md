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

# Run a single test class (Robolectric — no device needed)
./gradlew test --tests "com.example.clipboardman.util.CryptoManagerTest"

# Clean build
./gradlew clean

# Check for lint issues
./gradlew lint
```

Tests use **Robolectric 4.11.1** for Android unit tests (no emulator needed). Test classes live under `app/src/test/java/com/example/clipboardman/`.

## Project Overview

This is an **Android clipboard synchronization app** (Kotlin + Jetpack Compose, app ID `com.example.clipboardpush.plus`) that syncs clipboard content between Android and a PC client over LAN or via a relay server. Targets Android 8.0+ (API 26+), compiled with Java 17.

## Architecture

### Communication Architecture

The app supports two sync modes:

1. **Relay Server (Socket.IO)**: Clipboard text/files are exchanged through a Python relay server. The Android client connects via Socket.IO using `RelayRepository`. Text is AES-GCM encrypted before sending.

2. **LAN Direct Transfer ("Announce & Pull")**: For files, the PC announces availability via `file_available` signal (see `LAN_SIGNAL_PROTOCOL.md`). Android attempts a direct LAN HTTP download; if it fails, it sends `file_need_relay` to trigger a cloud upload fallback. See `LAN_SYNC_DEV_PLAN.md` for the full state machine.

### Socket.IO Protocol

- Join event sends `room`, `client_id`, `client_type`, `device_name`, `network info`, `protocol_version: "4.0"`
- Canonical LAN file events: `file_available` → `file_sync_completed` or `file_need_relay`
- Legacy aliases exist (`file_announcement`, `file_ack`, `file_request_relay`) — **receive-only** during transition
- V4 additions: `lan_probe_request` / `peer_evicted` / `room_state_changed` / `transfer_command` / `peer_network_update`

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
            ├── UploadWorker (LAN announce → cloud fallback, 8 s LAN ACK timeout)
            └── DownloadWorker (LAN pull → cloud fallback)
```

`ClipboardService` is the central coordinator. `MainActivity` binds to it via `LocalBinder` to expose service state to the UI. `MainViewModel` reads state from the service and exposes it as `StateFlow` for Compose.

### Key Files

| File | Purpose |
|------|---------|
| `service/ClipboardService.kt` | Foreground service; owns Socket.IO lifecycle, clipboard monitoring, file transfer orchestration |
| `data/repository/RelayRepository.kt` | Socket.IO client; emits `RelayEvent` sealed class to service |
| `data/repository/SettingsRepository.kt` | DataStore-backed settings (server URL, room ID, encryption key, feature flags) |
| `data/repository/MessageRepository.kt` | Persists and exposes clipboard message history |
| `ui/viewmodel/MainViewModel.kt` | UI state (connection, peers, messages) as `StateFlow` |
| `util/CryptoManager.kt` | AES-GCM encrypt/decrypt (AES-256, 12-byte IV, 128-bit tag); `encryptFile`/`decryptFile` for streams |
| `service/LocalFileServer.kt` | NanoHTTPD server for LAN file serving |
| `share/ShareReceiverActivity.kt` | Handles incoming `ACTION_SEND` intents (text, image, file) |

### RelayEvent Sealed Class

`RelayRepository` emits these variants to `ClipboardService`:

| Variant | Trigger |
|---------|---------|
| `ClipboardSync` | Received encrypted/plain text |
| `FileSync` | Cloud file download URL received |
| `FileAvailable` | LAN announcement (file_id, transfer_id, filename, local_url) |
| `FileSyncCompleted` | LAN success ACK from receiver |
| `FileNeedRelay` | Receiver requesting cloud fallback |
| `LanProbeRequest` | V4 reachability probe from PC |
| `PeerEvicted` | Peer removed from room |
| `RoomStateChanged` | Peer list updated |
| `TransferCommand` | Generic V4 command |

**SharedFlow configuration:** `connectionStatus`, `peerCount`, `peers` use `replay = 1` (latest value retained). The `events` flow uses `replay = 0, extraBufferCapacity = 10, DROP_OLDEST`.

### File Handle Modes

Defined in `SettingsRepository` and used by `DownloadWorker`:

| Constant | Value | Behavior |
|----------|-------|----------|
| `FILE_MODE_SAVE_LOCAL` | 0 | Save to Downloads, don't copy to clipboard |
| `FILE_MODE_COPY_REFERENCE` | 1 | Legacy fallback |
| `FILE_MODE_SAVE_AND_COPY_IMAGE` | 2 | Save; also copy image URI to clipboard if image |
| `FILE_MODE_CLIPBOARD_ONLY` | 3 | Copy to clipboard only, don't save |

### Notification Channels

Defined in `ClipboardManApp.kt`:
- `clipboard_service` — LOW priority, silent (foreground service notification)
- `clipboard_push` — HIGH priority with sound/vibration (received content)

### Peer Count Guard

`ClipboardService` skips sending clipboard content when `activePeerCount == 0`. Any UI "push" action should respect `MainViewModel.hasPeers`.

### Message Deduplication

`MessageRepository.addMessageAtomic()` deduplicates by `id` (timestamp-based) and `safeId` (fallback hash). When adding new message types, ensure they populate `id` to avoid duplicates across reconnects.

### Service Coroutine Scope

`ClipboardService` uses `CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)` with a `CoroutineExceptionHandler` to prevent uncaught exceptions from crashing the service. Network callbacks use a 1-second debounce delay before reconnecting.
