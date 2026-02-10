#include "ClipboardMonitor.h"
#include "core/Logger.h"

namespace ClipboardPush {
namespace Platform {

ClipboardMonitor& ClipboardMonitor::Instance() {
    static ClipboardMonitor instance;
    return instance;
}

bool ClipboardMonitor::Start(HWND hMessageWindow) {
    if (AddClipboardFormatListener(hMessageWindow)) {
        LOG_INFO("Clipboard monitor started");
        return true;
    }
    LOG_ERROR("Failed to start clipboard monitor");
    return false;
}

void ClipboardMonitor::Stop(HWND hMessageWindow) {
    RemoveClipboardFormatListener(hMessageWindow);
}

void ClipboardMonitor::HandleMessage(UINT message, WPARAM wParam, LPARAM lParam) {
    if (message == WM_CLIPBOARDUPDATE) {
        if (m_callback) m_callback();
    }
}

}
}
