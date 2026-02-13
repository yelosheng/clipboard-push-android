# Task Plan: Clipboard Push v3.2 (Telegram-style Notifications)

**Goal**: Implement a custom, high-quality popup notification window that mimics the "Telegram" aesthetic (rounded corners, smooth animation, modern typography).

## Phase 1: Custom Notification Window Class
- [ ] **NotificationWindow Class**: Define a new UI component for the popup.
- [ ] **Window Styling**: Implement a borderless, layered window (`WS_EX_LAYERED`) for transparency and shadows.
- [ ] **GDI+ Rendering**: Create a modern "bubble" look with rounded corners and a slight drop shadow.

## Phase 2: Animation & Layout
- [ ] **Animation Logic**: Implement a "slide up" entry and a "fade out" exit.
- [ ] **Dynamic Sizing**: Adjust bubble size based on the length of the received text.
- [ ] **Placement Logic**: Automatically position the bubble in the bottom-right corner, respecting the taskbar height.

## Phase 3: Integration
- [ ] **Main Integration**: Replace or augment `TrayIcon::ShowMessage` with the new `NotificationWindow`.
- [ ] **Thread Safety**: Ensure notifications can be triggered from network threads.

## Phase 4: Polish & Refinement
- [ ] **Multi-notification handling**: Support stacking multiple notifications if messages arrive quickly.
- [ ] **Visual Tweaks**: Refine colors (dark/light theme support) and fonts.

## Phase 5: Build & Release
- [ ] **Final Rebuild**: Ensure size remains optimized.
- [ ] **Release Update**: Update `dist-v3-win32`.
