# ROOM LAN State API

Version: v1.0.0
Date: 2026-02-13
Audience: PC Client (Win32), Relay Server (Flask-SocketIO), Android Client (Kotlin)
Status: Draft for implementation

## 1. Scope

This document defines a strict, unambiguous contract for:

- Two-peer room enforcement.
- LAN co-location detection using probe flow.
- Relay decision based on room LAN state.
- Event schemas for PC, Server, and Android.

This document is normative. If implementation conflicts with this doc, this doc wins.

## 2. Roles and Terms

- `pc`: Windows desktop sender. Must provide `probe_url`.
- `app`: Android receiver/prober.
- `room`: logical sync group. Max 2 peers.
- `peer`: one logical client device in room.
- `probe_id`: unique ID for a single LAN probe transaction.
- `transfer_id`: unique ID for one file transfer transaction.

## 3. Global Rules

1. Transport is Socket.IO default namespace `/`.
2. All payloads MUST be JSON object, not JSON string.
3. All business events MUST contain `room` and `protocol_version`.
4. Canonical protocol version string is `"4.0"`.
5. Server forwarding for peer-to-peer events MUST use:
   - `emit(event, data, room=room, include_self=False)`
6. Room capacity is exactly `max_peers = 2`.
7. On third peer join, server MUST evict the oldest active peer in that room.
8. `transfer_id` and `probe_id` MUST be globally unique for at least 24 hours.

## 4. Data Structures

## 4.1 PeerMeta (join payload)

```json
{
  "protocol_version": "4.0",
  "room": "room_abc",
  "client_id": "pc_Huang_230",
  "client_type": "pc",
  "joined_at_ms": 1770965204123,
  "network": {
    "private_ip": "192.168.1.5",
    "cidr": "192.168.1.0/24",
    "network_id_hash": "sha256:9f0d...",
    "network_epoch": 12
  },
  "probe": {
    "probe_url": "http://192.168.1.5:54321/probe",
    "probe_ttl_ms": 30000
  }
}
```

Field constraints:

- `protocol_version`: required, exact `"4.0"`.
- `room`: required, 1-64 chars, regex `^[A-Za-z0-9_-]+$`.
- `client_id`: required, globally unique device identity, 1-128 chars.
- `client_type`: required enum: `pc|app`.
- `joined_at_ms`: required, Unix epoch milliseconds.
- `network.private_ip`: required, IPv4 private ranges only for now:
  - `10.0.0.0/8`
  - `172.16.0.0/12`
  - `192.168.0.0/16`
- `network.cidr`: required, IPv4 CIDR string.
- `network.network_id_hash`: optional but recommended. Do not send raw SSID/BSSID.
- `network.network_epoch`: required non-negative integer; increment when local network changes.
- `probe.probe_url`:
  - required if `client_type=pc`
  - forbidden if `client_type=app`
  - must be `http://<private-ip>:<port>/probe` format
  - host must equal `network.private_ip`
- `probe.probe_ttl_ms`: required if `client_type=pc`, range `1000..120000`.

## 4.2 RoomState (server internal + broadcast)

```json
{
  "protocol_version": "4.0",
  "room": "room_abc",
  "max_peers": 2,
  "state": "PAIR_SAME_LAN",
  "same_lan": true,
  "lan_confidence": "confirmed",
  "last_probe": {
    "probe_id": "pr_1770965204_001",
    "status": "ok",
    "latency_ms": 23,
    "checked_at_ms": 1770965204999
  },
  "peers": [
    {
      "client_id": "pc_Huang_230",
      "client_type": "pc",
      "joined_at_ms": 1770965204123,
      "last_seen_ms": 1770965204999,
      "network_epoch": 12
    },
    {
      "client_id": "app_Pixel8_001",
      "client_type": "app",
      "joined_at_ms": 1770965204301,
      "last_seen_ms": 1770965204999,
      "network_epoch": 45
    }
  ]
}
```

State enum:

- `EMPTY`: no peers.
- `SINGLE`: one peer.
- `PAIR_UNKNOWN`: two peers, LAN not confirmed.
- `PAIR_SAME_LAN`: two peers, LAN probe confirmed success.
- `PAIR_DIFF_LAN`: two peers, LAN probe confirmed failure.

Lan confidence enum:

- `none`: no probe yet.
- `suspected`: heuristic only.
- `confirmed`: based on probe result.

## 4.3 TransferContext (server internal)

```json
{
  "transfer_id": "tr_1770965204_777",
  "room": "room_abc",
  "sender_client_id": "pc_Huang_230",
  "receiver_client_id": "app_Pixel8_001",
  "file": {
    "file_id": "f_1770965204_123",
    "filename": "image.png",
    "mime": "image/png",
    "size_bytes": 322122
  },
  "strategy": "lan_first",
  "status": "waiting_result",
  "created_at_ms": 1770965205000,
  "decision_deadline_ms": 1770965215000
}
```

Status enum:

- `created`
- `offered`
- `waiting_result`
- `lan_success`
- `fallback_requested`
- `fallback_timeout`
- `relay_uploading`
- `completed`
- `failed`

## 5. Event Definitions

All events below MUST include `protocol_version` and `room`.

## 5.1 `join` (peer -> server)

Purpose: register peer and join room.

Payload: `PeerMeta`.

Server behavior:

1. Validate payload.
2. If room has 2 peers, evict oldest peer.
3. Add/refresh peer.
4. Recompute `RoomState` and emit `room_state_changed`.
5. If room now has `pc+app`, emit `lan_probe_request` to app.

## 5.2 `peer_evicted` (server -> evicted peer)

```json
{
  "protocol_version": "4.0",
  "room": "room_abc",
  "evicted_client_id": "old_peer_1",
  "reason": "room_capacity_exceeded",
  "evicted_at_ms": 1770965206000
}
```

Receiver action:

- Stop sending business events for this room.
- Rejoin explicitly if needed.

## 5.3 `room_state_changed` (server -> room)

Payload: `RoomState`.

Emission triggers:

- Join/leave/disconnect/evict.
- Probe result received.
- Peer network epoch changed.
- Probe result expired.

## 5.4 `lan_probe_request` (server -> app)

```json
{
  "protocol_version": "4.0",
  "room": "room_abc",
  "probe_id": "pr_1770965204_001",
  "provider_client_id": "pc_Huang_230",
  "probe_url": "http://192.168.1.5:54321/probe",
  "timeout_ms": 1200,
  "requested_at_ms": 1770965206100
}
```

Server constraints:

- Send only to `app` peer.
- Reject probe_url if not private IP host.

## 5.5 `lan_probe_result` (app -> server)

```json
{
  "protocol_version": "4.0",
  "room": "room_abc",
  "probe_id": "pr_1770965204_001",
  "result": "ok",
  "latency_ms": 38,
  "http_status": 200,
  "reason": "",
  "reported_at_ms": 1770965206200
}
```

Field constraints:

- `result`: enum `ok|fail|timeout`.
- `latency_ms`: required if `result=ok`, otherwise optional.
- `http_status`: optional integer.
- `reason`: required if `result!=ok`.

Server behavior:

- Idempotent by `probe_id` (first valid result wins).
- Update room state:
  - `ok` -> `PAIR_SAME_LAN`
  - `fail|timeout` -> `PAIR_DIFF_LAN`
- Emit `room_state_changed`.

## 5.6 `file_available` (pc -> server -> app)

```json
{
  "protocol_version": "4.0",
  "room": "room_abc",
  "transfer_id": "tr_1770965204_777",
  "file_id": "f_1770965204_123",
  "filename": "image.png",
  "mime": "image/png",
  "size_bytes": 322122,
  "sender_client_id": "pc_Huang_230",
  "local_url": "http://192.168.1.5:54321/files/image.png",
  "decision_timeout_ms": 10000,
  "sent_at_ms": 1770965207000
}
```

Server behavior:

- Accept only from `pc` in that room.
- If room `PAIR_SAME_LAN`, forward to app and start decision timer.
- If room `PAIR_DIFF_LAN`, skip LAN and immediately emit `transfer_command` with `upload_relay` to pc.
- If room `PAIR_UNKNOWN`, either:
  - trigger probe then hold briefly, or
  - fallback policy: treat as diff_lan.

## 5.7 `file_sync_completed` (app -> server -> pc)

```json
{
  "protocol_version": "4.0",
  "room": "room_abc",
  "transfer_id": "tr_1770965204_777",
  "file_id": "f_1770965204_123",
  "method": "lan",
  "received_at_ms": 1770965208200
}
```

Server behavior:

- Validate transfer ownership and pending status.
- Mark transfer `lan_success`.
- Emit `transfer_command` action `finish` to pc.

## 5.8 `file_need_relay` (app -> server -> pc)

```json
{
  "protocol_version": "4.0",
  "room": "room_abc",
  "transfer_id": "tr_1770965204_777",
  "file_id": "f_1770965204_123",
  "reason": "timeout_or_unreachable",
  "reported_at_ms": 1770965208200
}
```

Server behavior:

- Validate transfer is pending.
- Mark transfer `fallback_requested`.
- Emit `transfer_command` action `upload_relay` to pc.

## 5.9 `transfer_command` (server -> pc)

```json
{
  "protocol_version": "4.0",
  "room": "room_abc",
  "transfer_id": "tr_1770965204_777",
  "file_id": "f_1770965204_123",
  "action": "upload_relay",
  "reason": "app_requested_fallback",
  "issued_at_ms": 1770965208300
}
```

Action enum:

- `finish`: stop fallback/upload path.
- `upload_relay`: upload via cloud relay now.

## 6. Timers and Expiry

- `probe timeout`: default 1200 ms.
- `probe result ttl`: 60000 ms.
- `transfer decision timeout`: default 10000 ms, max 30000 ms.
- Server owns timers. Clients should not own final decision timer.

## 7. Error Codes

Server emits `error` event payload:

```json
{
  "protocol_version": "4.0",
  "room": "room_abc",
  "code": "E_ROOM_CAPACITY",
  "message": "Room capacity exceeded; oldest peer evicted.",
  "details": {}
}
```

Code table:

- `E_BAD_SCHEMA`: missing/invalid field.
- `E_BAD_VERSION`: unsupported protocol_version.
- `E_ROOM_CAPACITY`: room full and eviction occurred.
- `E_PROBE_URL_INVALID`: probe_url not private or malformed.
- `E_PROBE_STALE`: probe_id expired/already finalized.
- `E_TRANSFER_UNKNOWN`: transfer_id not found.
- `E_TRANSFER_STATE`: event not allowed in current transfer state.
- `E_ROLE_DENIED`: role not allowed for this event.

## 8. Security Requirements

1. Reject non-private `probe_url` hosts.
2. Reject hostname-based probe_url, allow literal IPv4 private address only.
3. Enforce max payload size (recommended <= 32 KB for signaling).
4. Do not log secrets; file URLs may be partially masked in logs.
5. Use room-scoped authorization if auth layer exists.

## 9. Logging Contract

All parties log one line per important event with keys:

- `ts_ms`
- `room`
- `client_id`
- `event`
- `transfer_id` or `probe_id`
- `status`
- `reason`
- `latency_ms` (if any)

Example:

```text
ts_ms=1770965208200 room=room_abc client_id=app_Pixel8_001 event=lan_probe_result probe_id=pr_1770965204_001 status=ok latency_ms=38
```

## 10. Sequence (Happy Path)

1. pc join with probe_url.
2. app join.
3. server emits `lan_probe_request` to app.
4. app emits `lan_probe_result(ok)`.
5. room becomes `PAIR_SAME_LAN`.
6. pc emits `file_available`.
7. app LAN downloads file.
8. app emits `file_sync_completed`.
9. server emits `transfer_command(finish)` to pc.
10. transfer completed without cloud relay.

## 11. Sequence (Fallback Path)

1. steps 1-6 same as above.
2. app cannot fetch local_url.
3. app emits `file_need_relay`.
4. server emits `transfer_command(upload_relay)`.
5. pc uploads to cloud and emits legacy `file_sync` (outside this spec).

## 12. Backward Compatibility Window

During migration only, server MAY map aliases:

- `file_announcement` -> `file_available`
- `file_ack` -> `file_sync_completed`
- `file_request_relay` -> `file_need_relay`

Compatibility window should be time-boxed and removed after all peers upgrade.
