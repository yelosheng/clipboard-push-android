#pragma once
#include <windows.h>
#include <functional>

namespace ClipboardPush {
namespace Platform {

class ClipboardMonitor {
public:
    using Callback = std::function<void()>;

    static ClipboardMonitor& Instance();

    bool Start(HWND hMessageWindow);
    void Stop(HWND hMessageWindow);
    void SetCallback(Callback cb) { m_callback = cb; }
    
    void HandleMessage(UINT message, WPARAM wParam, LPARAM lParam);

private:
    ClipboardMonitor() = default;
    Callback m_callback;
};

}
}
