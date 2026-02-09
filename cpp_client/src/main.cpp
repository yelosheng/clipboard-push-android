#include "Common.hpp"
#include "ConfigManager.hpp"
#include "MainWindow.hpp"
#include <commctrl.h>
#include <gdiplus.h>

#pragma comment(lib, "gdiplus.lib")
#pragma comment(lib, "comctl32.lib")

int WINAPI wWinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance,
                    PWSTR pCmdLine, int nCmdShow) {
  // Init Common Controls
  INITCOMMONCONTROLSEX icex;
  icex.dwSize = sizeof(INITCOMMONCONTROLSEX);
  icex.dwICC = ICC_STANDARD_CLASSES | ICC_WIN95_CLASSES;
  InitCommonControlsEx(&icex);

  // Init Logging
  spdlog::set_pattern("[%Y-%m-%d %H:%M:%S.%e] [%l] %v");
  spdlog::info("Application starting...");

  // Init GDI+
  Gdiplus::GdiplusStartupInput gdiplusStartupInput;
  ULONG_PTR gdiplusToken;
  Gdiplus::GdiplusStartup(&gdiplusToken, &gdiplusStartupInput, nullptr);

  // Load Config
  ConfigManager::Instance().Load();

  // Network Client
  NetworkClient::Instance().Start();

  // Create Main Window
  ui::MainWindow mainWin;
  if (!mainWin.Create(L"Clipboard Man - Settings",
                      WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX,
                      0, 200, 200, 700, 450)) {
    return 0;
  }

  mainWin.Show(nCmdShow);

  // Message Loop
  MSG msg = {};
  while (GetMessage(&msg, nullptr, 0, 0)) {
    if (msg.message == WM_HOTKEY) {
      HotkeyManager::Instance().HandleHotkey((int)msg.wParam);
    }
    TranslateMessage(&msg);
    DispatchMessage(&msg);
  }

  // Cleanup GDI+
  Gdiplus::GdiplusShutdown(gdiplusToken);

  return 0;
}
