# Clipboard Push — Android

> Real-time clipboard sync between Android and PC, with end-to-end AES-256-GCM encryption.

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-purple.svg)](https://developer.android.com/jetpack/compose)

**Related:** [Website](https://clipboardpush.com) · [PC Client](https://github.com/clipboardpush/clipboard-push-win32) · [Relay Server](https://github.com/clipboardpush/clipboard-push-server) · [Privacy Policy](https://clipboardpush.com/privacy)

---

## What is this?

Clipboard Push is an Android clipboard sync tool that keeps your phone and PC clipboard in sync in real time. Copy something on your phone and it instantly appears on your PC — and vice versa. No account required, no data stored in the cloud.

## Features

- **Real-time sync** — clipboard content is pushed instantly with no perceptible delay
- **File transfer** — send images, documents, videos, and other files from phone to PC
- **AES-256-GCM encryption** — all content is encrypted before leaving your device; the relay server only sees ciphertext
- **LAN direct transfer** — files travel directly between devices over Wi-Fi, bypassing the cloud for maximum speed
- **QR code pairing** — scan the QR code shown by the PC client to pair; no registration needed
- **Self-hostable** — spin up a private relay server with Docker in minutes
- **Open source** — fully auditable code, no hidden data collection

## Quick Start

1. Install the app from [Google Play](https://play.google.com/store/apps/details?id=com.clipboardpush.plus) or download the APK from [Releases](https://github.com/clipboardpush/clipboard-push-android/releases)
2. Download and install the [PC client](https://github.com/clipboardpush/clipboard-push-win32/releases) (Windows)
3. Open the app → tap **Connect** → tap the QR icon → scan the QR code shown by the PC client
4. Start copying — content syncs automatically

## Architecture

```
Android App  ── Socket.IO (AES-256-GCM) ──► Relay Server ◄── Socket.IO (AES-256-GCM) ──  PC Client
                                                   │
                                           Cloud Storage R2 (file relay, optional)

On same Wi-Fi:  Android App ◄─── HTTP direct pull ─── PC Client  (bypasses relay server)
```

- **Text sync**: Android encrypts with AES-256-GCM and sends to the relay server; the PC client receives and decrypts
- **File transfer**: Android announces file availability; PC first attempts a direct LAN pull; if that fails, the file is uploaded to cloud storage for the PC to download
- **Pairing**: After scanning the QR code, both sides share a room ID and encryption key — the server never sees the plaintext key

For protocol details see [CLAUDE.md](CLAUDE.md) and [LAN_SIGNAL_PROTOCOL.md](LAN_SIGNAL_PROTOCOL.md).

## Building from Source

**Requirements:**
- Android Studio Hedgehog or newer
- JDK 17
- Android SDK API 34

```bash
git clone https://github.com/clipboardpush/clipboard-push-android.git
cd clipboard-push-android

# Build debug APK
./gradlew assembleDebug

# Run unit tests (Robolectric — no device needed)
./gradlew test
```

> **Note:** Firebase Cloud Messaging (FCM) infrastructure is present in the codebase but intentionally disabled — it does not affect normal use. No `google-services.json` is needed to build. See the "FCM Dormant State" section in [CLAUDE.md](CLAUDE.md) for re-enablement steps.

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

- All clipboard text is encrypted with **AES-256-GCM** before transmission
- The relay server only forwards ciphertext and cannot read the content
- Files transferred over LAN never pass through the relay server
- No user accounts, no email addresses, no personal data collected
- Full privacy policy: [clipboardpush.com/privacy](https://clipboardpush.com/privacy)

## Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) before submitting a PR.

Bug reports and feature requests: [GitHub Issues](https://github.com/clipboardpush/clipboard-push-android/issues)

## License

This project is licensed under the [Apache 2.0 License](LICENSE).
