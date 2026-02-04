# Clipboard Man Client - Gemini Context

This directory contains the client-side components of the **Clipboard Man** system, designed to synchronize clipboard data (text, images, and files) between Android devices and Windows PCs.

## Project Overview

The system operates on a client-server architecture where this directory houses the Windows client tools:

1.  **PC Listener (Client):** A background service that maintains a real-time Socket.IO connection to the server. It automatically downloads files/images and updates the Windows system clipboard when new content is received from other devices.
2.  **Push Tool:** A standalone CLI utility that captures the current Windows clipboard state and pushes it to the server via REST API.
3.  **Web Test Client:** A browser-based interface (HTML/JS) for testing server functionality and manually sending data.

### Key Technologies
- **Language:** Python 3.x
- **Network:** `python-socketio` (WebSocket/Socket.IO) for receiving, `requests` (HTTP) for sending.
- **OS Integration:** `pywin32` and `ctypes` for advanced Windows clipboard manipulation (CF_DIB, CF_HDROP).
- **UI/Logging:** `rich` for terminal UI, `loguru` for logging.

## Building and Running

### 1. Environment Setup
Ensure Python 3 is installed. Install dependencies using `pip`:

```powershell
pip install -r requirements.txt
```

### 2. Configuration
Edit `config.json` to match your environment. Key fields:
- `server_url`: The address of the Clipboard Man server (e.g., `http://localhost:9661`).
- `download_path`: Local directory to save received files (default is `~/Downloads/ClipboardMan`).

### 3. Running the Listener (Receiver)
To start receiving clipboard updates from the server:

```powershell
# Using the provided batch script (runs minimized)
.\start_client.bat

# Or running directly with Python
python clipboard_man_client.py
```

### 4. Running the Push Tool (Sender)
To push your current clipboard to the server:

```powershell
# Using the batch script
.\push_to_server.bat

# Or running directly with Python
python push_to_clipboard_man_server.py
```

### 5. Running the Web Test Client
Open `index.html` in a web browser. Alternatively, serve it via Python:

```powershell
python -m http.server 8080
# Access at http://localhost:8080
```

## Key Files

*   **`clipboard_man_client.py`**: The core listener script. It handles the Socket.IO connection (`sio.connect`), file downloads, and updating the Windows clipboard. It specifically handles `CF_DIB` for images and `CF_HDROP` for file lists to ensure native paste behavior in Explorer and other apps.
*   **`push_to_clipboard_man_server.py`**: The sender script. It detects the content type of the clipboard (File, Image, or Text) and posts it to the server's API (`/api/push/text` or `/api/push/file`).
*   **`config.json`**: Central configuration file. **Note:** This file is read by both Python scripts.
*   **`requirements.txt`**: List of Python dependencies.
*   **`start_client.bat`** & **`push_to_server.bat`**: Windows batch scripts for easy execution. `start_client.bat` is configured to run the client minimized.

## Development Conventions

*   **Platform Specificity:** The code is heavily optimized for Windows (`win32` platform checks). It uses `pywin32` for low-level clipboard access which is necessary for file copying (Explorer pasting support).
*   **Error Handling:** Network operations are wrapped in try-except blocks to handle server disconnections gracefully. The client attempts to reconnect automatically.
*   **User Feedback:** The `rich` library is used to provide colorful, panel-based output in the terminal to indicate status and incoming messages.
*   **File Handling:** Downloaded files are automatically renamed with a counter if a file with the same name already exists to prevent overwrites.
