#pragma once
#include <string>
#include <windows.h>
#include <functional>

namespace ClipboardPush {
namespace Platform {

class Hotkey {
public:
    using Callback = std::function<void()>;

    static Hotkey& Instance();
    
    // Parses "Ctrl+F6", "Alt+Shift+V" etc.
    bool Register(HWND hWnd, const std::string& hotkeyStr);
    void Unregister(HWND hWnd);
    
    void SetCallback(Callback cb) { m_callback = cb; }
    void HandleMessage(WPARAM wParam);

private:
    Hotkey() = default;
    Callback m_callback;
    int m_id = 1001;
    UINT m_fsModifiers = 0;
    UINT m_vk = 0;
};

}
}
