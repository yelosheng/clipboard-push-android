# Clipboard Man Relay Server API

This document describes the HTTP and Socket.IO interfaces used by clients.

## Base URL

Use the host and port where the relay server is running, for example:

```
http://127.0.0.1:5055
```

In production, this is typically behind Nginx with HTTPS.

## Authentication (Dashboard Only)

The dashboard uses form-based auth via Flask-Login. API and Socket.IO do not require auth.

- `GET /login` renders the login form.
- `POST /login` accepts form fields:
  - `password` (required)
  - `remember` (optional checkbox)
- `GET /logout` clears the session.
- `GET /dashboard` renders the dashboard (login required).

## HTTP APIs

### `POST /api/file/upload_auth`

Request a presigned upload URL for Cloudflare R2. The server returns both upload and download URLs.

Request JSON:
```
{
  "filename": "example.png",
  "content_type": "image/png"
}
```

Response JSON (200):
```
{
  "upload_url": "https://...presigned PUT...",
  "download_url": "https://...presigned GET...",
  "file_key": "1700000000_example.png",
  "expires_in": 300
}
```

Errors:
- `400` if `filename` is missing.

Notes:
- Upload to `upload_url` with HTTP PUT.
- Then send `download_url` to peers via `file_push`.

### `POST /api/relay`

Stateless relay endpoint for CLI tools or Shortcuts. Broadcasts a Socket.IO event into a room.

Request JSON:
```
{
  "room": "room-1",
  "event": "clipboard_sync",
  "data": { "room": "room-1", "content": "..." },
  "sender_id": "device-123"
}
```

Response JSON (200):
```
{ "status": "ok" }
```

Errors:
- `400` if `room`, `event`, or `data` is missing.
- `500` for unexpected errors.

Notes:
- If `sender_id` is provided, the server skips that client's active sessions.
- `sender_id` is only used by this endpoint; Socket.IO payloads should use `client_id`.

## Socket.IO Events

Connect using the Socket.IO client library to the same base URL.

### Connection & Reconnect Guidance

- Use automatic reconnection (default in Socket.IO). If customizing, use a backoff strategy:
  - `reconnection: true`
  - `reconnectionAttempts: Infinity`
  - `reconnectionDelay: 500`
  - `reconnectionDelayMax: 5000`
- On reconnect, always re-send `join` with the latest `room`, `client_id`, and `client_type`.
- Treat connection loss as transient; do not clear local clipboard history unless the user asks.

### Error Handling & Retry Guidance

- `status` events are informational and should not be treated as errors.
- `error` events indicate server-side rejection; fix the payload before retrying.
- For HTTP APIs:
  - `400` means the request is malformed. Do not retry until fixed.
  - `500` means transient server error. Retry with exponential backoff.
- For Socket.IO emits:
  - If the emit fails due to disconnection, queue it locally and retry after reconnect.
  - Avoid infinite loops on application-level errors.

### E2EE Payload Conventions

The relay is transport-only. Clients should encrypt clipboard/file payloads end-to-end.

Recommended fields (example):
```
{
  "room": "room-1",
  "client_id": "device-123",
  "content": "base64(ciphertext)",
  "alg": "AES-256-GCM",
  "iv": "base64(iv)",
  "kid": "key-id-or-device-id"
}
```

Notes:
- `content`, `iv`, and optional `kid` are client-defined.
- The server does not validate encryption fields.

### Client -> Server

#### `join`

Join a room and register client identity.

Payload:
```
{
  "room": "room-1",
  "client_id": "device-123",
  "client_type": "android"
}
```

Rules:
- `client_id` registers the session under a stable device identifier.
- `client_type` is required when `client_id` is provided.
- Recommended `client_type` values: `windows`, `macos`, `linux`, `android`, `ios`, `web`, `cli`.

#### `leave`

Leave a room.

Payload:
```
{ "room": "room-1" }
```

#### `clipboard_push`

Broadcast encrypted clipboard content to other room members.

Payload:
```
{
  "room": "room-1",
  "content": "base64-or-encrypted-text",
  "client_id": "device-123"
}
```

Notes:
- Content is expected to be encrypted by the client.
- The server forwards the payload as `clipboard_sync`.

#### `file_push`

Broadcast file metadata to other room members. The payload should include the presigned download URL.

Payload:
```
{
  "room": "room-1",
  "filename": "example.png",
  "download_url": "https://...presigned GET...",
  "key": "optional-decryption-key",
  "client_id": "device-123"
}
```

Notes:
- The server forwards the payload as `file_sync`.

### Server -> Client

#### `status`

Sent to the room after `join` or `leave`.

Payload:
```
{ "msg": "Joined room: room-1" }
```

#### `clipboard_sync`

Relayed clipboard message to other room members. Payload is the same as `clipboard_push`.

#### `file_sync`

Relayed file metadata to other room members. Payload is the same as `file_push`.

#### `room_stats`

Broadcast when room membership changes.

Payload:
```
{
  "count": 2,
  "room": "room-1",
  "clients": ["device-123", "device-456"]
}
```

#### `error`

Emitted when the server rejects a request.

Payload:
```
{ "msg": "client_type is required when providing client_id" }
```

### Dashboard-Only Events

The dashboard joins the `dashboard_room` and receives:

#### `client_list_update`

Current active clients and sessions.

Payload:
```
{
  "device-123": { "sids": ["sid1"], "room": "room-1", "type": "android" }
}
```

#### `activity_log`

Activity entries for clipboard, files, and API relay.

Payload:
```
{ "type": "clipboard", "room": "room-1", "sender": "device-123", "content": "Preview..." }
```

#### `server_stats`

Simple connection counters.

Payload:
```
{ "clients": 3, "msg": "New connection" }
```

