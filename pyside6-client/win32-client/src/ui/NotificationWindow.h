#pragma once
#include <windows.h>
#include <objbase.h>
#include <string>
#include <vector>
#include <memory>

namespace ClipboardPush {
namespace UI {

enum class NotificationStyle {
    Inbound,  // Received from other devices
    Outbound  // Sent from this device
};

struct NotificationData {
    std::wstring title;
    std::wstring message;
    NotificationStyle style;
};

class NotificationWindow {
public:
    static void RegisterClass(HINSTANCE hInst);
    static void Show(const std::wstring& title, const std::wstring& message, NotificationStyle style = NotificationStyle::Inbound);
    static void HandleMessage(LPARAM lParam);

private:
    NotificationWindow(const std::wstring& title, const std::wstring& message, NotificationStyle style);
    ~NotificationWindow();

    static LRESULT CALLBACK WndProc(HWND hWnd, UINT message, WPARAM wParam, LPARAM lParam);
    void Render();
    void Animate();
    void PositionWindow();

    HWND m_hWnd;
    std::wstring m_title;
    std::wstring m_message;
    NotificationStyle m_style;
    
    int m_alpha = 0;
    int m_targetAlpha = 255;
    int m_yPos = 0;
    int m_targetY = 0;
    int m_state = 0; // 0: FadeIn, 1: Stay, 2: FadeOut
    DWORD m_startTime = 0;

    static std::vector<HWND> s_activeNotifications;
};

}
}
