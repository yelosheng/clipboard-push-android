#include "ClipboardManager.hpp"
#include "ConfigManager.hpp"
#include "CryptoManager.hpp"
#include "HotkeyManager.hpp"
#include "NetworkClient.hpp"
#include <shellapi.h>
#include <spdlog/sinks/basic_file_sink.h>
#include <spdlog/spdlog.h>
#include <thread>
#include <windows.h>


// IDs
#define ID_TRAY_ICON 1001
#define WM_TRAY_ICON (WM_USER + 1)
#define ID_MENU_EXIT 2001
#define ID_MENU_SETTINGS 2002

// Globals
NOTIFYICONDATA nid;
HWND g_hwnd;
NetworkClient *g_network = nullptr;

LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
  switch (msg) {
  case WM_CREATE:
    // Add Clipboard Listener
    AddClipboardFormatListener(hwnd);

    // Register Hotkeys
    {
      auto &cfg = ConfigManager::Instance().GetConfig();
      if (cfg.hotkey_key > 0) {
        HotkeyManager::Instance().Register(
            cfg.hotkey_mod, cfg.hotkey_key, []() {
              spdlog::info("Global Hotkey Triggered! Pushing Clipboard...");
              // Trigger logic usually handled by checking clipboard content and
              // pushing But here we need to trigger manually? For now we just
              // log. Ideally: g_network->ManualPush();
            });
      }
    }

    // Setup Tray Icon
    nid.cbSize = sizeof(NOTIFYICONDATA);
    nid.hWnd = hwnd;
    nid.uID = ID_TRAY_ICON;
    nid.uFlags = NIF_ICON | NIF_MESSAGE | NIF_TIP;
    nid.uCallbackMessage = WM_TRAY_ICON;
    nid.hIcon = LoadIcon(NULL, IDI_APPLICATION); // Default icon
    wcscpy_s(nid.szTip, L"Clipboard Man (Win32)");
    Shell_NotifyIcon(NIM_ADD, &nid);
    break;

  case WM_DESTROY:
    RemoveClipboardFormatListener(hwnd);
    HotkeyManager::Instance().UnregisterAll();
    Shell_NotifyIcon(NIM_DELETE, &nid);
    PostQuitMessage(0);
    break;

  case WM_HOTKEY:
    HotkeyManager::Instance().HandleHotkey(wParam);
    break;

  case WM_CLIPBOARDUPDATE:
    // Process clipboard change
    // Logic handled by ClipboardManager if needed, or we explicitly check
    // For now, let's just log.
    // In real app, we check if content changed and push if auto-copy is on.
    // But NetworkClient handles *incoming*.
    // *Outgoing* needs to be triggered here.
    // ClipboardManager::Instance().OnClipboardUpdate();
    spdlog::info("Clipboard updated");
    break;

  case WM_TRAY_ICON:
    if (lParam == WM_RBUTTONUP) {
      POINT pt;
      GetCursorPos(&pt);
      HMENU hMenu = CreatePopupMenu();
      AppendMenuW(hMenu, MF_STRING, ID_MENU_SETTINGS, L"Settings (TODO)");
      AppendMenuW(hMenu, MF_STRING, ID_MENU_EXIT, L"Exit");
      SetForegroundWindow(hwnd);
      TrackPopupMenu(hMenu, TPM_BOTTOMALIGN | TPM_LEFTALIGN, pt.x, pt.y, 0,
                     hwnd, NULL);
      DestroyMenu(hMenu);
    }
    break;

  case WM_COMMAND:
    if (LOWORD(wParam) == ID_MENU_EXIT) {
      DestroyWindow(hwnd);
    }
    break;

  default:
    return DefWindowProc(hwnd, msg, wParam, lParam);
  }
  return 0;
}

int WINAPI WinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance,
                   LPSTR lpCmdLine, int nCmdShow) {
  // Config Logger
  auto logger = spdlog::basic_logger_mt("file_logger", "logs/client.txt");
  spdlog::set_default_logger(logger);
  spdlog::flush_on(spdlog::level::info);
  spdlog::info("Starting Clipboard Man Win32 Client...");

  // Load Config
  ConfigManager::Instance().Load();

  // Crypto Key
  std::string key = ConfigManager::Instance().GetConfig().room_key;
  if (!key.empty()) {
    CryptoManager::Instance().SetKey(key);
  }

  // Network
  g_network = new NetworkClient();
  g_network->Start();

  // Window Class
  const wchar_t *className = L"ClipboardManClass";
  WNDCLASSEX wc = {0};
  wc.cbSize = sizeof(WNDCLASSEX);
  wc.lpfnWndProc = WndProc;
  wc.hInstance = hInstance;
  wc.lpszClassName = className;
  RegisterClassEx(&wc);

  // Create Hidden Window
  g_hwnd = CreateWindowEx(0, className, L"Clipboard Man Helper", 0, 0, 0, 0, 0,
                          NULL, NULL, hInstance, NULL);

  // Message Loop
  MSG msg;
  while (GetMessage(&msg, NULL, 0, 0)) {
    TranslateMessage(&msg);
    DispatchMessage(&msg);
  }

  delete g_network;
  return (int)msg.wParam;
}
