# GEMINI.md - Clipboard Man Relay Server

This document provides a technical overview and developer context for the Clipboard Man Relay Server project.

## Project Overview

Clipboard Man Relay Server is a Python-based backend that facilitates real-time clipboard synchronization and file transfers between multiple devices (e.g., Android and PC). It acts as a signaling and relay hub, ensuring that clipboard content and file metadata are efficiently distributed to all devices within a specific "room."

### Key Technologies
- **Framework:** [Flask](https://flask.palletsprojects.com/) (Web server)
- **Real-time Communication:** [Flask-SocketIO](https://flask-socketio.readthedocs.io/) (WebSockets for instant signaling)
- **File Storage:** [Cloudflare R2](https://www.cloudflare.com/products/r2/) (via `boto3`) using presigned URLs for direct client-to-cloud uploads.
- **Authentication:** [Flask-Login](https://flask-login.readthedocs.io/) for the web-based management dashboard.
- **Environment Management:** `python-dotenv`.

### Architecture
1. **Socket.IO Signaling:** Devices join specific rooms to sync clipboard data (`clipboard_push`) and file metadata (`file_push`).
2. **Stateless API Relay:** An HTTP POST endpoint (`/api/relay`) allows external tools (CLI, Shortcuts) to push data into the WebSocket rooms.
3. **Presigned Uploads:** To handle files without saturating server bandwidth, the server generates presigned R2 URLs (`/api/file/upload_auth`), allowing clients to upload directly to Cloudflare.
4. **Monitoring Dashboard:** A protected web route (`/dashboard`) provides real-time visibility into active client sessions and activity logs. The dashboard uses Socket.IO to receive live updates (`client_list_update`, `activity_log`) without page refreshes.

## Building and Running

### Prerequisites
- Python 3.8+
- A Cloudflare R2 bucket and API credentials.

### Installation
1. Install dependencies:
   ```bash
   pip install -r requirements.txt
   ```
2. Configure environment variables:
   Copy `.env.example` to `.env` and fill in the required values:
   - `FLASK_SECRET_KEY`: Secure random string.
   - `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET_NAME`: Your Cloudflare R2 details.
   - `ADMIN_PASSWORD`: Password for the dashboard.

### Running Locally
Start the server in debug mode:
```bash
python relay_server.py
```
The server defaults to port `5055`.

### Production Deployment
As detailed in `DEPLOY_DEBIAN.md`, use a production WSGI server like Gunicorn with the Gevent worker class:
```bash
gunicorn --worker-class gevent --workers 1 --bind 127.0.0.1:5000 relay_server:app
```
It is recommended to use Nginx as a reverse proxy for SSL termination and WebSocket support.

## Development Conventions

- **Real-time Events:**
  - `join` / `leave`: Room management.
  - `clipboard_push`: Client sends (typically E2EE encrypted) text data.
  - `clipboard_sync`: Server broadcasts text to other room members.
  - `file_push`: Client sends file metadata (download URL, key).
  - `file_sync`: Server broadcasts file metadata.
- **Security:** Clipboard data is expected to be End-to-End Encrypted (E2EE) by the clients before reaching the relay.
- **Logging:** Uses Python's standard `logging` module. Check console output for connection/disconnection events and R2 connection status.
- **Static Assets:** CSS and JS for the dashboard are located in `static/`, with HTML templates in `templates/`.
