# Findings & Tech Stack - Custom Notifications

## Telegram-style UI Implementation in pure Win32

### 1. Window Architecture
- **Window Type**: `WS_POPUP` with `WS_EX_LAYERED` and `WS_EX_TOPMOST`.
- **Transparency**: Use `UpdateLayeredWindow` for per-pixel alpha transparency (crucial for smooth rounded corners and shadows).
- **Class**: A dedicated `NotificationWindow` class that manages its own message loop or uses the main one.

### 2. Rendering (GDI+)
- **Background**: Rounded rectangle with a subtle gradient or solid "Telegram Blue" / "Dark Gray".
- **Shadow**: A feathered alpha-channel shadow around the bubble.
- **Icon**: Small app icon or "sync" icon inside the bubble.
- **Text**: `Gdiplus::Font` using "Segoe UI" or "Inter" if available.

### 3. Animation
- **Timers**: Use `SetTimer` for the animation ticks (60fps target).
- **Logic**: 
    - Entry: Alpha 0 -> 255 and Y-offset slide.
    - Stay: Static for 3-5 seconds.
    - Exit: Alpha 255 -> 0.

### 4. DPI Awareness
- The window must use `GetDpiForWindow` to scale sizes, paddings, and font sizes correctly on 4K screens.

### 5. Interaction
- Clicking the notification should focus the main window or clear the notification immediately.
- Hovering over it should pause the "fade out" timer.
