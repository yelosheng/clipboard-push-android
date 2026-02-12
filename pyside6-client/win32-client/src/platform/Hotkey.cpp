#include "Hotkey.h"
#include "core/Logger.h"
#include <vector>
#include <sstream>
#include <algorithm>

namespace ClipboardPush {
namespace Platform {

Hotkey& Hotkey::Instance() {
    static Hotkey instance;
    return instance;
}

struct KeyMap { const char* name; UINT vk; };
static const KeyMap vk_map[] = {
    {"F1", VK_F1}, {"F2", VK_F2}, {"F3", VK_F3}, {"F4", VK_F4}, {"F5", VK_F5}, {"F6", VK_F6},
    {"F7", VK_F7}, {"F8", VK_F8}, {"F9", VK_F9}, {"F10", VK_F10}, {"F11", VK_F11}, {"F12", VK_F12},
    {"A", 'A'}, {"V", 'V'}, {"C", 'C'}, {"X", 'X'} // Add more as needed
};

bool Hotkey::Register(HWND hWnd, const std::string& hotkeyStr) {
    Unregister(hWnd);
    
    m_fsModifiers = 0;
    m_vk = 0;
    
    std::stringstream ss(hotkeyStr);
    std::string part;
    while (std::getline(ss, part, '+')) {
        // Trim whitespace
        part.erase(std::remove(part.begin(), part.end(), ' '), part.end());
        
        if (part == "Ctrl" || part == "Control") m_fsModifiers |= MOD_CONTROL;
        else if (part == "Alt") m_fsModifiers |= MOD_ALT;
        else if (part == "Shift") m_fsModifiers |= MOD_SHIFT;
        else if (part == "Win") m_fsModifiers |= MOD_WIN;
        else {
            for (const auto& km : vk_map) {
                if (part == km.name) { m_vk = km.vk; break; }
            }
            if (m_vk == 0 && part.length() == 1) m_vk = part[0];
        }
    }

    if (m_vk == 0) {
        LOG_ERROR("Invalid hotkey string: %s", hotkeyStr.c_str());
        return false;
    }

    if (RegisterHotKey(hWnd, m_id, m_fsModifiers | MOD_NOREPEAT, m_vk)) {
        LOG_INFO("Hotkey registered: %s", hotkeyStr.c_str());
        return true;
    }
    
    LOG_ERROR("Failed to register hotkey: %lu", GetLastError());
    return false;
}

void Hotkey::Unregister(HWND hWnd) {
    UnregisterHotKey(hWnd, m_id);
}

void Hotkey::HandleMessage(WPARAM wParam) {
    if (wParam == m_id && m_callback) {
        m_callback();
    }
}

}
}
