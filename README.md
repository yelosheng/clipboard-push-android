# Clipboard Push — Android

> Push your clipboard between Android and PC instantly, with end-to-end AES-256-GCM encryption.

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-purple.svg)](https://developer.android.com/jetpack/compose)

**Related:** [Website](https://clipboardpush.com) · [PC Client](https://github.com/clipboardpush/clipboard-push-win32) · [Relay Server](https://github.com/clipboardpush/clipboard-push-server) · [Privacy Policy](https://clipboardpush.com/privacy)

---

## What is this?

Clipboard Push is an Android app that lets you push clipboard content between your phone and PC on demand. Press a hotkey on your PC, or tap the Push button on your phone — the clipboard content transfers instantly. No account required.

The core model is **push-on-demand**: you decide when to send. There is no silent background sync that fires on every copy.

## Features

- **Push on demand** — tap the Push button on Android or press the hotkey on PC to transfer clipboard content instantly
- **File transfer** — send images, documents, and videos between phone and PC in both directions
- **AES-256-GCM encryption** — all content is encrypted before leaving your device; the relay server only sees ciphertext
- **LAN direct transfer** — files travel directly between devices over Wi-Fi for maximum speed; cloud relay is the fallback
- **QR code pairing** — scan the QR code shown by the PC client to pair; no registration needed
- **Self-hostable** — spin up a private relay server with Docker in minutes
- **Open source** — fully auditable code, no hidden data collection

## Quick Start

1. Download the APK from [Releases](https://github.com/clipboardpush/clipboard-push-android/releases)
2. Download and install the [PC client](https://github.com/clipboardpush/clipboard-push-win32/releases) (Windows)
3. Open the app → tap **Connect** → tap the QR icon → scan the QR code shown by the PC client
4. Tap **Push** on Android, or press the hotkey on PC — clipboard content transfers instantly

## Architecture

```
Android App  ── Socket.IO (AES-256-GCM) ──► Relay Server ◄── Socket.IO (AES-256-GCM) ──  PC Client
                                                   │
                                           Cloud storage or local disk (file relay, optional)

On same Wi-Fi:  Android App ◄──── HTTP direct pull ────► PC Client  (bypasses relay server)
```

- **Text push**: content is AES-256-GCM encrypted on-device and relayed through the server; the server never sees plaintext
- **File transfer**: the sender serves the encrypted file over HTTP; the receiver attempts a direct LAN pull first; if that fails, the file is uploaded to cloud storage (encrypted) for the receiver to download. Files are not permanently stored.
- **Pairing**: scanning the QR code shares a room ID and encryption key between both sides — the server never sees the plaintext key

For protocol details see the [Relay Server API](https://github.com/clipboardpush/clipboard-push-server/blob/master/RELAY_SERVER_API.md).

## Building from Source

**Requirements:**
- Android Studio Hedgehog or newer
- JDK 17
- Android SDK API 35

```bash
git clone https://github.com/clipboardpush/clipboard-push-android.git
cd clipboard-push-android

# Build debug APK
./gradlew assembleDebug

# Run unit tests (Robolectric — no device needed)
./gradlew test
```

> **Note:** Firebase Cloud Messaging (FCM) infrastructure is present in the codebase but intentionally disabled — it does not affect normal use. Building requires a `google-services.json` file (due to the Firebase Gradle plugin). You can obtain one by creating a placeholder Firebase project at [console.firebase.google.com](https://console.firebase.google.com) and registering the app with package name `com.clipboardpush.plus`.

## Self-Hosting the Relay Server

Prefer not to use the public server? Stand up a private one in minutes:

```bash
git clone https://github.com/clipboardpush/clipboard-push-server.git
cd clipboard-push-server
cp .env.example .env
# Edit .env with your configuration
docker-compose up -d
```

Then in the app: **Settings → Server Address** → enter your server's address and port.

See the [relay server repository](https://github.com/clipboardpush/clipboard-push-server) for full deployment instructions.

## Privacy

- All clipboard text is encrypted with **AES-256-GCM** before transmission; the relay server only sees ciphertext
- Files transferred over LAN never pass through the relay server
- When files are relayed through cloud storage (R2) or local server storage, they are encrypted end-to-end; the relay server automatically purges all stored files every 60 minutes, minimizing data exposure time
- No user accounts, no email addresses, no personal data collected
- Full privacy policy: [clipboardpush.com/privacy](https://clipboardpush.com/privacy)

## Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) before submitting a PR.

Bug reports and feature requests: [GitHub Issues](https://github.com/clipboardpush/clipboard-push-android/issues)

## License

This project is licensed under the [Apache 2.0 License](LICENSE).
