# Clipboard Push - LAN Direct Sync Development Plan

**Version:** 1.0  
**Date:** 2026-02-13  
**Target Platforms:** Windows (C++ Win32), Android (Kotlin)

---

## 1. Overview & Objective

**Objective:** Enable high-speed, direct file transfers between PC and Android devices when they are on the same Local Area Network (LAN), bypassing the cloud relay server for data transmission.

**Architecture: "Hybrid Relay + Direct"**
-   **Control Plane (Signaling):** Continues to use the Cloud Relay (Socket.IO) for reliable connection establishment, clipboard text sync, and signaling file availability.
-   **Data Plane (Transfer):** Opportunistically switches to direct HTTP transfer over LAN for files/images.
-   **Fallback:** Unexpected network conditions (Firewall, AP Isolation) will automatically trigger a fallback to Cloud Relay for seamless user experience.

---

## 2. Discovery Protocol (QR Code Update)

To enable direct connection, the devices must know each other's local IP address. The simplest discovery method (MVP) is to embed this information in the pairing QR Code.

**Current QR Payload:**
```json
{
  "server": "https://kxkl.tk:12561",
  "room": "room_123",
  "key": "aes_key_base64..."
}
```

**New QR Payload:**
```json
{
  "server": "https://kxkl.tk:12561",
  "room": "room_123",
  "key": "aes_key_base64...",
  "local_ip": "192.168.1.5",       // [NEW] PC's Local IPv4
  "local_port": 54321              // [NEW] Local HTTP Server Port
}
```

---

## 3. PC Client Implementation (Win32 C++)

### 3.1 Technology Stack
-   **Library:** `cpp-httplib` (Header-only, lightweight).
-   **Integration:** `vcpkg install cpp-httplib`.

### 3.2 Core Components
1.  **`LocalServer` Class**:
    -   Runs on a dedicated background thread.
    -   Binds to `0.0.0.0` on a random port (Range: 50000-60000).
    -   **Lifecycle**: Starts on app launch, stops on exit.

2.  **API Endpoints**:

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `GET` | `/ping` | Connectivity check. Returns `200 OK`. |
| `POST` | `/upload` | Receives file from Android. Saves to `Downloads`. Returns `200` on success. |
| `GET` | `/files/<filename>` | Serves a specific file from `Downloads` to Android. |

### 3.3 Security Logic
-   **Auth**: Validate `room_id` in headers (e.g., `X-Room-ID: room_123`) to prevent unauthorized local access.
-   **Path Traversal**: Sanitize `<filename>` in `GET /files/...` to ensure it only serves from the allowed Download directory.

### 3.4 Implementation Checklist
- [ ] Add `cpp-httplib` to `vcpkg.json`.
- [ ] Implement `Utils::GetLocalIPAddress()` using `GetAdaptersAddresses` (Win32 IP Helper API).
- [ ] Create `src/core/LocalServer.h/.cpp`.
- [ ] Update `SettingsWindow.cpp` to include `local_ip` & `local_port` in QR JSON.
- [ ] Add strict firewall disclaimer/handling (Windows Firewall will prompt on first run).

---

## 4. Android Client Implementation

### 4.1 Data Model Changes
-   **`SettingsRepository`**: Add `peerLocalIp` (String) and `peerLocalPort` (Int).
-   **Scan Logic**: Parse new fields from QR code and save to DataStore.

### 4.2 Local Discovery Logic
-   **When to check**: When `WorkManager` starts an Upload/Download job.
-   **How to check**: `HEAD http://<peerIP>:<peerPort>/ping`. Timeout: 2 seconds.
    -   If Success (200 OK): Use **Local Mode**.
    -   If Fail: Use **Cloud Mode**.

### 4.3 Transfer Logic (Smart Routing)

#### Case A: Phone Sending File to PC (Upload)
1.  Worker starts. Checks Local Mode.
2.  **If Local**:
    -   `POST` file to `http://<peerIP>:<peerPort>/upload`.
    -   On Success: Send "File Sent" signal to Relay (so PC knows to notify user).
    -   On Fail: Catch exception, switch to Cloud Upload.
3.  **If Cloud** (Default/Fallback):
    -   Request Presigned URL from Relay -> Upload to Cloud Storage.

#### Case B: PC Sending File to Phone (Download)
1.  PC sends `file_sync` event via Relay.
    -   Payload includes BOTH `download_url` (Cloud) AND `local_filename`.
2.  Android receives event. `DownloadWorker` starts.
3.  **Smart Download**:
    -   Construct `localUrl = http://<peerIP>:<peerPort>/files/<filename>`.
    -   Try `GET localUrl`.
    -   If Success: Save file, notify user.
    -   If Fail: Fallback to `GET download_url` (Cloud).

---

## 5. Security & Privacy Considerations

1.  **Local Encryption**:
    -   **Current**: HTTP (Plaintext). Safe enough for Home LANs (WPA2/3).
    -   **Future**: Could implement `AES-GCM` on the HTTP stream itself, utilizing the shared `room_key`.
    -   **Recommendation**: For MVP, allow plaintext HTTP on LAN, as setting up HTTPS with self-signed certs causes unavoidable trust errors on Android using standard HttpClients.

2.  **Network Isolation**:
    -   Public Wi-Fi (Coffee Shops, Airports) often enables "AP Isolation", blocking Client-to-Client communication.
    -   **Mitigation**: The system *must* seamlessly fallback to Cloud Relay in these scenarios without user intervention.

3.  **IP Address Changes**:
    -   PC IP may change via DHCP.
    -   **Solution**: PC Client should periodically (e.g., every 10 mins or on IP change detection) send a `system_info` event via Relay containing the new `local_ip`. Android updates its record.

---

## 6. Verification Plan

1.  **Environment Setup**: Connect PC and Phone to the same Wi-Fi.
2.  **Pairing**: Scan updated QR Code. Verify `peerLocalIp` is saved in Android logs.
3.  **Firewall Test**: Ensure Windows Firewall prompt is accepted for the PC Client.
4.  **Transfer Test**:
    -   Send a large video (100MB+) from Phone -> PC.
    -   Verify transfer speed is > 10MB/s (typical Wi-Fi 5 speed).
    -   Verify PC logs show "Httplib" request handling.
5.  **Fallback Test**:
    -   Disable Wi-Fi on Phone (use 5G/LTE).
    -   Send file.
    -   Verify transfer still succeeds via Cloud Relay (slower).
