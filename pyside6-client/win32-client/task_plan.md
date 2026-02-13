# Task Plan: Clipboard Push v4.0 (Signaling Driven LAN Sync)

**Goal**: Implement the "Room LAN State API" (v1.0.0) to achieve deterministic LAN/Cloud switching driven by server-side state.

## Phase 1: Local Server & Metadata (PC Identity)
- [ ] **LocalServer Enhancement**: Add `/probe` endpoint (returns 200 OK).
- [ ] **Utils Upgrade**: Implement CIDR calculation and Network Epoch tracking.
- [ ] **Join Refactor**: Update `SocketIOService::JoinRoom` to send the full `PeerMeta` (Protocol 4.0).

## Phase 2: Server-to-PC Commands
- [ ] **Handle `transfer_command`**: Implement listener for `finish` and `upload_relay`.
- [ ] **Handle `peer_evicted`**: Implement auto-rejoin logic if evicted.
- [ ] **Handle `room_state_changed`**: (Optional) Update tray icon or UI with LAN confidence level.

## Phase 3: Push State Machine 4.0
- [ ] **Refactor `PushFileData`**:
    1. Save to `temp/`.
    2. Emit `file_available` (v4.0 schema).
    3. Wait for `transfer_command`.
    4. Execute action (Cloud Upload or Cleanup).

## Phase 4: Visuals & Polish
- [ ] **Notification Refinement**: Show "LAN Mode" vs "Cloud Mode" in bubble notifications.
- [ ] **Robustness**: Ensure the 10s fallback still exists but is only a "deadman switch" (last resort).

## Phase 5: Verification
- [ ] Test `PAIR_SAME_LAN` path (Immediate LAN pull).
- [ ] Test `PAIR_DIFF_LAN` path (Immediate Server-directed upload).
