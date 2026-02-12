# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Clipboard Push is a Windows desktop client for real-time clipboard synchronization between PC and Android devices. Built with PySide6 (Qt6 Python bindings), it connects to a self-hosted relay server via Socket.IO for signaling and HTTP REST for data transfer. All clipboard content is end-to-end encrypted using AES-256-GCM.

## Commands

```bash
# Setup virtual environment
python -m venv venv
.\venv\Scripts\activate

# Install dependencies
pip install -r requirements.txt

# Run the application
python main_pyside.py
```

## Architecture

### Core Components

- **main_pyside.py** - Application entry point and main controller (`ClipboardApp` class)
  - Manages config loading/saving from `config.json`
  - Coordinates all workers and windows
  - Handles clipboard operations (text, images, files) using Win32 API
  - Push/receive logic for encrypted clipboard sync

- **worker_threads.py** - Background workers running in QThreads
  - `NetworkWorker`: Socket.IO client for real-time events (`clipboard_sync`, `file_sync`)
  - `HotkeyWorker`: Global hotkey listener using pynput

- **ui_manager.py** - Qt widgets
  - `MainWindow`: Text input with push button
  - `SettingsWindow`: Configuration dialog with QR pairing code
  - `QRCodeLabel`: Generates pairing QR codes
  - `HotkeyRecorderEdit`: Custom widget to capture key combinations

- **crypto_utils.py** - AES-256-GCM encryption wrapper
  - Format: `[12-byte nonce] + [ciphertext] + [16-byte tag]`
  - Compatible with Python cryptography library's AESGCM

### Data Flow

1. **Push (PC → Server → Mobile)**:
   - Hotkey triggers `on_hotkey_triggered()` → detects content type (file/image/text)
   - Content encrypted → POST to `/api/relay` (text) or upload to R2 then relay (files)

2. **Receive (Mobile → Server → PC)**:
   - Socket.IO events (`clipboard_sync`/`file_sync`) trigger handlers
   - Content decrypted → written to system clipboard using `pyperclip` or Win32 API

### Windows-Specific

The app uses Win32 API (`win32clipboard`, `win32con`) for advanced clipboard formats:
- `CF_HDROP` for file paths (copy/paste files in Explorer)
- `CF_DIB` for images (copy/paste bitmaps)

### Configuration

`config.json` stores:
- `relay_server_url`: Server address
- `room_id`/`room_key`: Pairing credentials (auto-generated on first run)
- `push_hotkey`: Global shortcut (default: Ctrl+F6)
- `download_path`: Where received files are saved
- `auto_copy_image`/`auto_copy_file`: Auto-paste received content

## Related

See `spec_for_cpp_rewrite.md` for detailed specifications intended for porting to C++/Qt6.
