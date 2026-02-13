#include "Platform.h"
#include <gdiplus.h>

namespace ClipboardPush {
namespace Platform {

ULONG_PTR gdiplusToken;

void Init() {
    Gdiplus::GdiplusStartupInput gdiplusStartupInput;
    Gdiplus::GdiplusStartup(&gdiplusToken, &gdiplusStartupInput, NULL);
}

void Shutdown() {
    Gdiplus::GdiplusShutdown(gdiplusToken);
}

HICON CreateStatusIcon(HINSTANCE hInst, int resourceId, COLORREF statusColor) {
    // 1. Load base icon
    HICON hBaseIcon = (HICON)LoadImageW(hInst, MAKEINTRESOURCEW(resourceId), IMAGE_ICON, 32, 32, LR_DEFAULTCOLOR);
    if (!hBaseIcon) return NULL;

    // 2. Convert to GDI+ Bitmap
    Gdiplus::Bitmap* bmp = Gdiplus::Bitmap::FromHICON(hBaseIcon);
    DestroyIcon(hBaseIcon); // Don't need the base handle anymore
    
    if (!bmp) return NULL;

    // 3. Draw status circle
    Gdiplus::Graphics graphics(bmp);
    graphics.SetSmoothingMode(Gdiplus::SmoothingModeAntiAlias);

    float iconSize = 32.0f;
    float circleSize = 10.0f;
    float x = iconSize - circleSize - 1.0f;
    float y = iconSize - circleSize - 1.0f;

    // Draw background/border for the circle
    Gdiplus::SolidBrush whiteBrush(Gdiplus::Color(255, 255, 255));
    graphics.FillEllipse(&whiteBrush, x - 1.0f, y - 1.0f, circleSize + 2.0f, circleSize + 2.0f);

    // Draw the status color
    Gdiplus::Color color;
    color.SetFromCOLORREF(statusColor);
    Gdiplus::SolidBrush statusBrush(color);
    graphics.FillEllipse(&statusBrush, x, y, circleSize, circleSize);

    // 4. Convert back to HICON
    HICON hResult = NULL;
    bmp->GetHICON(&hResult);

    delete bmp;
    return hResult;
}

}
}
