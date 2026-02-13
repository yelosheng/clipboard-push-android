# LAN Signal Protocol

Version: v3.3  
Date: 2026-02-13  
Scope: Clipboard Push Plus (PC / Relay Server / Android)

## 1. Transport Rules

- Use Socket.IO on default namespace `/`.
- All signal payloads MUST be JSON objects. Do not send stringified JSON.
- All LAN signal events MUST include `room`.
- Relay Server forwarding rule for LAN signals:
  - `emit(event, data, room=room, include_self=False)`

## 2. Join Prerequisite (Required)

Both PC and Android MUST join the same room before file signaling.

Example:

```json
{
  "event": "join",
  "data": {
    "room": "room_abc",
    "client_id": "pc_Huang_230",
    "client_type": "windows"
  }
}
```

## 3. Canonical Events (Single Source of Truth)

Only these three event names are canonical.

### 3.1 `file_available` (PC -> Server -> Android)

```json
{
  "room": "room_abc",
  "file_id": "f_1770965204_123",
  "filename": "image.png",
  "type": "image",
  "sender_id": "pc_Huang_230",
  "local_url": "http://192.168.1.5:54321/files/image.png"
}
```

### 3.2 `file_sync_completed` (Android -> Server -> PC)

```json
{
  "room": "room_abc",
  "file_id": "f_1770965204_123",
  "method": "lan"
}
```

### 3.3 `file_need_relay` (Android -> Server -> PC)

```json
{
  "room": "room_abc",
  "file_id": "f_1770965204_123",
  "reason": "timeout_or_unreachable"
}
```

## 4. Runtime State Machine

### 4.1 PC Flow

1. Save file locally.
2. Emit `file_available`.
3. Wait 5-10 seconds.
4. If `file_sync_completed` arrives: stop fallback upload.
5. If `file_need_relay` arrives: upload to cloud immediately.
6. If timeout: upload to cloud.

### 4.2 Android Flow

1. On `file_available`, try `GET local_url`.
2. If LAN download success: emit `file_sync_completed`.
3. If LAN download fails (timeout/unreachable/HTTP error): emit `file_need_relay`.

## 5. Compatibility Mapping (Transition Only)

During migration, receivers MAY support old aliases:

- `file_announcement` -> `file_available`
- `file_ack` -> `file_sync_completed`
- `file_request_relay` -> `file_need_relay`

PC sender SHOULD emit canonical names only.

## 6. Required Debug Logs

### 6.1 PC

- Log event send with: event name, room, file_id.

### 6.2 Relay Server

- Log forward with: `Relayed <event> to room: <room>`.

### 6.3 Android

- Log raw args for incoming `file_available`.
- Log parsed fields: room, file_id, local_url.
- Log emitted ack/fallback events with room and file_id.

## 7. Validation Checklist

1. PC and Android join same room.
2. PC emits `file_available` (with room).
3. Server logs relay of `file_available`.
4. Android receives event and attempts LAN pull.
5. Android emits `file_sync_completed` or `file_need_relay`.
6. Server relays response event.
7. PC reacts immediately (stop fallback or start cloud upload).
