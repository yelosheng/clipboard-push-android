# Findings - v4.0 Architecture Integration

## 1. PC Metadata Collection (PeerMeta)
- **Private IP**: Use existing `GetLocalIPAddress`.
- **CIDR**: Calculate based on subnet mask.
- **Probe URL**: `http://<ip>:<port>/probe`.
- **Network Epoch**: We can use a simple counter incremented whenever the IP address changes.

## 2. Local Server Role (The Probe Provider)
- The PC acts as the **Server** for the probe.
- Must respond quickly to `GET /probe`.
- Must not require `X-Room-ID` for the probe itself (to keep it ultra-simple) or we include it in the `probe_url` as a param. 
- *Correction*: The spec says `probe_url` must match `network.private_ip`. Let's stick to standard `200 OK`.

## 3. Server-Side Control
- This is the biggest change. PC no longer decides when to upload.
- If the Server emits `transfer_command(upload_relay)`, PC starts uploading.
- If the Server emits `transfer_command(finish)`, PC cleans up.
- This removes the "contradictory logs" where PC uploads while Phone downloads.

## 4. Eviction Handling
- In a 2-peer room, if a 3rd joins (e.g. PC restarts and gets new SID), the old SID is evicted.
- PC must handle `peer_evicted` and potentially re-register if it was the one evicted.
