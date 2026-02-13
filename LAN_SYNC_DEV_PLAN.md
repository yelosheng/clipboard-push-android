# Clipboard Push - LAN Direct Sync Development Plan (v2.0)

**Version:** 2.0 (Announce & Pull)  
**Date:** 2026-02-13  
**Objective:** Transition from "Always Upload" to "Opportunistic LAN Transfer" to achieve zero cloud traffic and near-instant synchronization when devices are co-located.

---

## 1. Architecture: "Announce & Pull" Model

Instead of the PC immediately pushing data to the cloud, it now "announces" its availability.

1.  **Announcement**: PC saves the file locally and broadcasts an availability signal via the Relay (Signaling only).
2.  **Pull**: Android receives the signal and attempts a high-speed LAN download from the PC.
3.  **Fallback**: If the LAN pull fails (not on same network, firewall, etc.), Android requests the PC to perform a traditional Cloud Upload.

---

## 2. Communication Protocol (New Signaling)

The following Socket.IO events must be implemented to manage the state machine.

### 2.1 Event: `file_announcement` (PC -> App)
Sent when the user triggers a push on the PC.
```json
{
  "event": "file_available",
  "data": {
    "file_id": "unique_id_123",
    "filename": "screenshot.png",
    "type": "image",
    "local_url": "http://192.168.1.5:54321/files/screenshot.png",
    "sender_id": "pc_Huang_230"
  }
}
```

### 2.2 Event: `file_ack` (App -> PC)
Sent when the App successfully downloads the file via LAN. PC can then stop its waiting timer.
```json
{
  "event": "file_sync_completed",
  "data": { "file_id": "unique_id_123", "method": "lan" }
}
```

### 2.3 Event: `file_request_relay` (App -> PC)
Sent when the App's LAN attempt fails or it detects it is on a different network (e.g., LTE).
```json
{
  "event": "file_need_relay",
  "data": { "file_id": "unique_id_123", "reason": "timeout_or_unreachable" }
}
```

---

## 3. Component Implementation Requirements

### 3.1 PC Client (Win32 C++)
- **Stage 1 (Buffer)**: On push, save file to `temp/` folder. Do NOT call Cloud Upload yet.
- **Stage 2 (Announce)**: Send `file_available` via Socket.IO.
- **Stage 3 (Wait)**: Start a 5-second "Decision Timer".
    - If `file_sync_completed` received: Success! Cleanup `temp/` immediately.
    - If `file_need_relay` received: Stop timer, proceed to Stage 4 (Upload).
    - If Timeout (5s): Proceed to Stage 4 (Upload).
- **Stage 4 (Legacy Push)**: Standard Cloud Upload -> Send original `file_sync` event.

### 3.2 Android Client (Kotlin)
- **Logic Branching**: 
    - On `file_available`:
        1. Attempt `GET local_url`.
        2. If success (200 OK): Notify user, send `file_sync_completed`.
        3. If fail (Timeout/404/NetworkError): Send `file_need_relay`.
    - On `file_sync` (Traditional):
        - Direct Cloud Download (existing logic).

### 3.3 Relay Server (Python)
- **Message Passing**: No logic changes needed to the core, but `relay_server.py` should be updated to ensure these new event names are permitted in the `activity_log` for the dashboard.
- **Protocol Stability**: Ensure Socket.IO `emit` correctly excludes the sender to prevent echoing the announcement back to the PC.

---

## 4. Security & Robustness

1.  **Unique IDs**: `file_id` must be unique per session to prevent `ack` collisions.
2.  **Tokenized Local Access**: The `local_url` could optionally include a one-time token if the `X-Room-ID` header is deemed insufficient for public LANs.
3.  **Graceful Degeneracy**: If the PC app crashes during Stage 3, the App simply won't get the file. The next time the user tries, the system resets.

---

## 5. Development Roadmap

- [ ] **PC**: Refactor `PushFileData` into an asynchronous state machine.
- [ ] **PC**: Implement listener for `file_need_relay`.
- [ ] **Android**: Implement `AnnouncementReceiver` and LAN Download client.
- [ ] **Android**: Implement `RelayRequester` logic.
- [ ] **Testing**: Large file (500MB+) transfer test. Verify 0% Cloud usage.
