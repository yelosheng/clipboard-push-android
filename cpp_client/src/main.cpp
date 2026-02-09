#include "ClipboardManager.hpp"
#include "ConfigManager.hpp"
#include "CryptoManager.hpp"
#include "HotkeyManager.hpp"
#include "NetworkClient.hpp"
#include "resource.h"
#include <shellapi.h>
#include <spdlog/sinks/basic_file_sink.h>
#include <spdlog/spdlog.h>
#include <thread>
#include <windows.h>


// IDs
// IDs
#define WM_TRAY_ICON (WM_USER + 1)

// Globals
NOTIFYICONDATA nid;
HWND g_hwnd;
NetworkClient *g_network = nullptr;

// Helper to convert std::string (UTF-8) to std::wstring (UTF-16)
std::wstring ToWString(const std::string &str) {
  if (str.empty())
    return std::wstring();
  int size_needed =
      MultiByteToWideChar(CP_UTF8, 0, &str[0], (int)str.size(), NULL, 0);
  std::wstring wstrTo(size_needed, 0);
  MultiByteToWideChar(CP_UTF8, 0, &str[0], (int)str.size(), &wstrTo[0],
                      size_needed);
  return wstrTo;
}

// Helper to convert std::wstring (UTF-16) to std::string (UTF-8)
std::string ToString(const std::wstring &wstr) {
  if (wstr.empty())
    return std::string();
  int size_needed = WideCharToMultiByte(CP_UTF8, 0, &wstr[0], (int)wstr.size(),
                                        NULL, 0, NULL, NULL);
  std::string strTo(size_needed, 0);
  WideCharToMultiByte(CP_UTF8, 0, &wstr[0], (int)wstr.size(), &strTo[0],
                      size_needed, NULL, NULL);
  return strTo;
}

// Settings Window Global
HWND g_hSettingsWnd = NULL;

// Control IDs
#define IDC_SET_URL 3001
#define IDC_SET_KEY 3002
#define IDC_BIT_SAVE 3003
#define IDC_BTN_CANCEL 3004

LRESULT CALLBACK SettingsWndProc(HWND hwnd, UINT msg, WPARAM wParam,
                                 LPARAM lParam) {
  switch (msg) {
  case WM_CREATE: {
    // Font
    HFONT hFont = (HFONT)GetStockObject(DEFAULT_GUI_FONT);

    // Server URL
    CreateWindowW(L"STATIC", L"Server URL:", WS_VISIBLE | WS_CHILD, 20, 20, 100,
                  20, hwnd, NULL, NULL, NULL);
    HWND hUrl = CreateWindowW(
        L"EDIT", L"", WS_VISIBLE | WS_CHILD | WS_BORDER | ES_AUTOHSCROLL, 20,
        40, 340, 25, hwnd, (HMENU)IDC_SET_URL, NULL, NULL);
    SendMessage(hUrl, WM_SETFONT, (WPARAM)hFont, TRUE);

    // Room Key
    CreateWindowW(L"STATIC", L"Room Key:", WS_VISIBLE | WS_CHILD, 20, 75, 100,
                  20, hwnd, NULL, NULL, NULL);
    HWND hKey = CreateWindowW(
        L"EDIT", L"",
        WS_VISIBLE | WS_CHILD | WS_BORDER | ES_AUTOHSCROLL | ES_PASSWORD, 20,
        95, 340, 25, hwnd, (HMENU)IDC_SET_KEY, NULL, NULL);
    SendMessage(hKey, WM_SETFONT, (WPARAM)hFont, TRUE);

    // Buttons
    HWND hSave = CreateWindowW(
        L"BUTTON", L"Save", WS_VISIBLE | WS_CHILD | BS_DEFPUSHBUTTON, 200, 140,
        80, 30, hwnd, (HMENU)IDC_BIT_SAVE, NULL, NULL);
    SendMessage(hSave, WM_SETFONT, (WPARAM)hFont, TRUE);

    HWND hCancel =
        CreateWindowW(L"BUTTON", L"Cancel", WS_VISIBLE | WS_CHILD, 290, 140, 80,
                      30, hwnd, (HMENU)IDC_BTN_CANCEL, NULL, NULL);
    SendMessage(hCancel, WM_SETFONT, (WPARAM)hFont, TRUE);

    // Load Values
    auto &config = ConfigManager::Instance().GetConfig();
    SetWindowTextW(hUrl, ToWString(config.server_url).c_str());
    SetWindowTextW(hKey, ToWString(config.room_key).c_str());
  } break;

  case WM_COMMAND:
    if (LOWORD(wParam) == IDC_BIT_SAVE) {
      wchar_t buf[1024];

      GetWindowTextW(GetDlgItem(hwnd, IDC_SET_URL), buf, 1024);
      std::string newUrl = ToString(buf);

      GetWindowTextW(GetDlgItem(hwnd, IDC_SET_KEY), buf, 1024);
      std::string newKey = ToString(buf);

      auto &config = ConfigManager::Instance().GetConfig();
      config.server_url = newUrl;
      config.room_key = newKey;
      ConfigManager::Instance().Save();

      MessageBoxW(hwnd, L"Settings saved. Please restart the application.",
                  L"Saved", MB_OK | MB_ICONINFORMATION);
      DestroyWindow(hwnd);
    } else if (LOWORD(wParam) == IDC_BTN_CANCEL) {
      DestroyWindow(hwnd);
    }
    break;

  case WM_DESTROY:
    g_hSettingsWnd = NULL;
    break;

  default:
    return DefWindowProc(hwnd, msg, wParam, lParam);
  }
  return 0;
}

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
      AppendMenuW(hMenu, MF_STRING, ID_MENU_SETTINGS,
                  L"Settings"); // Removed (TODO)
      AppendMenuW(hMenu, MF_STRING, ID_MENU_EXIT, L"Exit");
      SetForegroundWindow(hwnd);
      int cmd =
          TrackPopupMenu(hMenu, TPM_RETURNCMD | TPM_BOTTOMALIGN | TPM_LEFTALIGN,
                         pt.x, pt.y, 0, hwnd, NULL);
      DestroyMenu(hMenu);

      if (cmd == ID_MENU_SETTINGS) {
        if (g_hSettingsWnd) {
          ShowWindow(g_hSettingsWnd, SW_SHOW);
          SetForegroundWindow(g_hSettingsWnd);
        } else {
          WNDCLASSEXW wc = {0};
          wc.cbSize = sizeof(WNDCLASSEX);
          wc.style = CS_HREDRAW | CS_VREDRAW;
          wc.lpfnWndProc = SettingsWndProc;
          wc.hInstance = GetModuleHandle(NULL);
          wc.hCursor = LoadCursor(NULL, IDC_ARROW);
          wc.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
          wc.lpszClassName = L"ClipboardManSettings";
          RegisterClassExW(&wc);

          g_hSettingsWnd = CreateWindowW(
              L"ClipboardManSettings", L"Settings",
              WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX,
              CW_USEDEFAULT, CW_USEDEFAULT, 400, 220, NULL, NULL,
              GetModuleHandle(NULL), NULL);

          ShowWindow(g_hSettingsWnd, SW_SHOW);
          UpdateWindow(g_hSettingsWnd);
        }
      } else if (cmd == ID_MENU_EXIT) {
        DestroyWindow(hwnd);
      }
    }
    break;

    // ... inside WinMain (no changes needed) ...

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
