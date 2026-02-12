# Task Plan: Clipboard Push v3.1 (Systray Status Icons)

**Goal**: Implement a dynamic system tray icon that indicates connection status using color-coded "indicator lights" overlaid on the original app icon.

## Phase 1: Foundation for Dynamic Icons
- [ ] **GDI+ Helper**: Create or update Platform logic to support loading resources and drawing overlays.
- [ ] **Icon Compositor**: Implement a function to take a base icon, a status color, and return a new `HICON`.

## Phase 2: State Management Expansion
- [ ] **Refine ConnectionStatus**: Ensure we have enough states (Disconnected, Connecting, ConnectedLonely, ConnectedSynced).
- [ ] **Socket.IO Integration**: Update `SocketIOService` to handle potential "room peer" information if available, or simulate it.

## Phase 3: Tray Icon Integration
- [ ] **Update TrayIcon Class**: Add a method to change the icon dynamically.
- [ ] **Lifecycle Management**: Ensure dynamically created icons are properly destroyed to prevent GDI leaks.

## Phase 4: UI & Logic Wiring
- [ ] **Connect Signals**: Link `SocketIOService` status changes to `TrayIcon` updates in `main.cpp`.
- [ ] **Visual Polish**: Adjust indicator position, size, and border for optimal visibility on different DPIs.

## Phase 5: Testing & Release
- [ ] **Functional Test**: Verify icons change correctly during connect/disconnect/reconnect.
- [ ] **Resource Test**: Verify no memory leaks after multiple status changes.
- [ ] **Final Build**: Perform Release build and update `dist-v3-win32`.
