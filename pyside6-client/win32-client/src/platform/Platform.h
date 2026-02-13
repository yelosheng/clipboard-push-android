#pragma once
#include <winsock2.h>
#include <windows.h>
#include <objbase.h>

namespace ClipboardPush {
namespace Platform {

void Init();
void Shutdown();

// Creates a new HICON with a status badge. Caller MUST call DestroyIcon.
HICON CreateStatusIcon(HINSTANCE hInst, int resourceId, COLORREF statusColor);

}
}
