#include "Platform.h"
#include <windows.h>
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

}
}
