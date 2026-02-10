#include <windows.h>
#include "core/Logger.h"
#include "core/Config.h"
#include "platform/Platform.h"
#include "platform/Clipboard.h"
#include "platform/Hotkey.h"
#include "ui/TrayIcon.h"
#include "ui/MainWindow.h"
#include "ui/SettingsWindow.h"
#include "ui/Resource.h"

#define WM_TRAYICON (WM_USER + 1)

LRESULT CALLBACK WndProc(HWND hWnd, UINT message, WPARAM wParam, LPARAM lParam) {
    switch (message) {
    case WM_TRAYICON:
        if (LOWORD(lParam) == WM_RBUTTONUP) {
            ClipboardPush::UI::TrayIcon::Instance().ShowContextMenu(hWnd);
        } else if (LOWORD(lParam) == WM_LBUTTONDBLCLK) {
            ClipboardPush::UI::MainWindow::Instance().Show();
        }
        break;
    case WM_COMMAND:
        if (LOWORD(wParam) == IDM_TRAY_EXIT) {
            PostQuitMessage(0);
        } else if (LOWORD(wParam) == IDM_TRAY_OPEN) {
            ClipboardPush::UI::MainWindow::Instance().Show();
        } else if (LOWORD(wParam) == IDM_TRAY_SETTINGS) {
            ClipboardPush::UI::SettingsWindow::Instance().Show();
        } else if (LOWORD(wParam) == IDM_TRAY_PUSH) {
            LOG_INFO("Manual Push Triggered");
            ClipboardPush::UI::TrayIcon::Instance().ShowMessage(L"Clipboard Push", L"Push logic is under development...");
        }
        break;
    case WM_HOTKEY:
        ClipboardPush::Platform::Hotkey::Instance().HandleMessage(wParam);
        break;
    case WM_DESTROY:
        PostQuitMessage(0);
        break;
    default:
        return DefWindowProc(hWnd, message, wParam, lParam);
    }
    return 0;
}

int WINAPI wWinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, PWSTR pCmdLine, int nCmdShow) {
    ClipboardPush::Platform::Init();
    ClipboardPush::Config::Instance().Load();
    auto& data = ClipboardPush::Config::Instance().Data();
    
    // Create a dummy window to handle messages
    const wchar_t CLASS_NAME[] = L"ClipboardPushMessageWindow";
    WNDCLASSW wc = {};
    wc.lpfnWndProc = WndProc;
    wc.hInstance = hInstance;
    wc.lpszClassName = CLASS_NAME;
    RegisterClassW(&wc);

    HWND hWnd = CreateWindowExW(0, CLASS_NAME, L"Clipboard Push v3.0", 0, 0, 0, 0, 0, HWND_MESSAGE, NULL, hInstance, NULL);
    if (!hWnd) return 0;

    // Init UI
    ClipboardPush::UI::MainWindow::Instance().Create(hInstance);
    ClipboardPush::UI::SettingsWindow::Instance().Create(hInstance);
    ClipboardPush::UI::TrayIcon::Instance().Init(hWnd, hInstance);

    // Register Hotkey
    ClipboardPush::Platform::Hotkey::Instance().SetCallback([]() {
        LOG_INFO("Hotkey Triggered!");
        ClipboardPush::UI::TrayIcon::Instance().ShowMessage(L"Hotkey Triggered", L"Scanning clipboard...");
    });
    ClipboardPush::Platform::Hotkey::Instance().Register(hWnd, data.push_hotkey);

    // Show UI
    if (!data.start_minimized) {
        ClipboardPush::UI::MainWindow::Instance().Show();
    } else {
        ClipboardPush::UI::TrayIcon::Instance().ShowMessage(L"Clipboard Push v3.0", L"I am now running ultra-light in your system tray!");
    }

    // Message Loop
    MSG msg = {};
    while (GetMessage(&msg, NULL, 0, 0)) {
        TranslateMessage(&msg);
        DispatchMessage(&msg);
    }

    ClipboardPush::UI::TrayIcon::Instance().Remove();
    ClipboardPush::Platform::Shutdown();
    return 0;
}