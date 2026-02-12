# Findings & Tech Stack - Systray Indicators

## Dynamic Icon Composition Strategy

### 1. Drawing the "Badge" or "Light"
- **Method**: Use `Gdiplus::Graphics` object to draw on a `Gdiplus::Bitmap`.
- **Steps**:
    1.  Load the base icon from resources into a `Gdiplus::Bitmap`.
    2.  Get Graphics context from that bitmap.
    3.  Set SmoothingMode to `SmoothingModeAntiAlias`.
    4.  Draw a small circle in the bottom-right quadrant.
    5.  Fill with state-specific color.
    6.  Draw a 1px white or dark border around the circle for contrast.
    7.  Convert `Gdiplus::Bitmap` back to `HICON` using `GetHICON()`.

### 2. State to Color Mapping
- **Disconnected (Red/Gray)**: `Gdiplus::Color(128, 128, 128)` (Gray) or `Color(255, 0, 0)` (Red). *User requested Gray for Disconnected.*
- **Lonely (Yellow)**: `Gdiplus::Color(255, 215, 0)` (Gold). *Connected but no peers.*
- **Synced (Green)**: `Gdiplus::Color(50, 205, 50)` (LimeGreen). *Connected and ready.*

### 3. Detecting "Room Peers"
- **Problem**: Does the server provide room occupancy?
- **Analysis**: If not, we can infer "Synced" if we receive a message from a `client_id` that is not our own.
- **Alternative**: Check Socket.IO handshake response. Some servers send a `sid` and room info.

### 4. GDI+ Memory Management
- **Warning**: Every call to `GetHICON()` creates a new handle that **must** be destroyed with `DestroyIcon()`.
- **Strategy**: The `TrayIcon` class should store the current `HICON` and destroy the previous one before applying a new one.

### 5. High DPI Considerations
- The base icon should be the largest available (e.g., 256x256) or we should detect the current system icon size using `GetSystemMetrics(SM_CXSMICON)`.
- **Choice**: Composition should happen at standard small icon size (usually 16x16 or 32x32 depending on DPI) to keep it sharp.
