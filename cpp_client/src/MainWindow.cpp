#include "MainWindow.hpp"
#include "Common.hpp"
#include "ConfigManager.hpp"
#include "HotkeyManager.hpp"
#include "NetworkClient.hpp"
#include <strsafe.h>

#define WM_TRAY_MSG (WM_USER + 100)

namespace ui {

MainWindow::MainWindow() {}
MainWindow::~MainWindow() {}

enum { ID_BTN_SAVE = 101, ID_BTN_CANCEL = 102 };

LRESULT MainWindow::HandleMessage(UINT uMsg, WPARAM wParam, LPARAM lParam) {
  switch (uMsg) {
  case WM_CREATE: {
    CreateControls();
    HICON hIcon =
        (HICON)LoadImage(GetModuleHandle(nullptr), IDI_APPLICATION, IMAGE_ICON,
                         0, 0, LR_DEFAULTSIZE | LR_SHARED);
    m_trayIcon = std::make_unique<TrayIcon>(m_hwnd, 1, WM_TRAY_MSG, hIcon,
                                            L"Clipboard Man");
    m_trayIcon->Add();
    return 0;
  }

  case WM_PAINT: {
    PAINTSTRUCT ps;
    HDC hdc = BeginPaint(m_hwnd, &ps);
    RenderQRCode(hdc);
    EndPaint(m_hwnd, &ps);
    return 0;
  }

  case WM_TRAY_MSG:
    if (lParam == WM_LBUTTONDBLCLK || (lParam == WM_CONTEXTMENU)) {
      ShowWindow(m_hwnd, SW_SHOW);
      SetForegroundWindow(m_hwnd);
    }
    return 0;

  case WM_COMMAND:
    if (LOWORD(wParam) == ID_BTN_SAVE) {
      OnSave();
    } else if (LOWORD(wParam) == ID_BTN_CANCEL) {
      ShowWindow(m_hwnd, SW_HIDE);
    }
    return 0;

  case WM_CLOSE:
    ShowWindow(m_hwnd, SW_HIDE);
    return 0;

  case WM_DESTROY:
    PostQuitMessage(0);
    return 0;
  }
  return Window::HandleMessage(uMsg, wParam, lParam);
}

void MainWindow::CreateControls() {
  int x = 20, y = 20, labelWidth = 100, inputWidth = 300, h = 25;
  auto hInst = GetModuleHandle(nullptr);

  // Create Font
  HFONT hFont =
      CreateFontW(18, 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE, DEFAULT_CHARSET,
                  OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
                  DEFAULT_PITCH | FF_DONTCARE, L"Segoe UI");

  auto SetControlFont = [hFont](HWND hWnd) {
    if (hWnd)
      SendMessage(hWnd, WM_SETFONT, (WPARAM)hFont, TRUE);
  };

  // Labels and Inputs
  HWND hCtrl;
  hCtrl = CreateWindowW(L"Static", L"Server URL:", WS_VISIBLE | WS_CHILD, x, y,
                        labelWidth, h, m_hwnd, nullptr, hInst, nullptr);
  SetControlFont(hCtrl);
  m_hServerUrl = CreateWindowExW(
      WS_EX_CLIENTEDGE, L"Edit", L"", WS_VISIBLE | WS_CHILD | ES_AUTOHSCROLL,
      x + labelWidth, y, inputWidth, h, m_hwnd, nullptr, hInst, nullptr);
  SetControlFont(m_hServerUrl);

  y += 40;
  hCtrl = CreateWindowW(L"Static", L"Download Path:", WS_VISIBLE | WS_CHILD, x,
                        y, labelWidth, h, m_hwnd, nullptr, hInst, nullptr);
  SetControlFont(hCtrl);
  m_hDownloadPath = CreateWindowExW(
      WS_EX_CLIENTEDGE, L"Edit", L"", WS_VISIBLE | WS_CHILD | ES_AUTOHSCROLL,
      x + labelWidth, y, inputWidth, h, m_hwnd, nullptr, hInst, nullptr);
  SetControlFont(m_hDownloadPath);

  y += 40;
  hCtrl = CreateWindowW(L"Static", L"Push Hotkey:", WS_VISIBLE | WS_CHILD, x, y,
                        labelWidth, h, m_hwnd, nullptr, hInst, nullptr);
  SetControlFont(hCtrl);
  m_hPushHotkey = CreateWindowExW(
      WS_EX_CLIENTEDGE, L"Edit", L"", WS_VISIBLE | WS_CHILD | ES_AUTOHSCROLL,
      x + labelWidth, y, inputWidth, h, m_hwnd, nullptr, hInst, nullptr);
  SetControlFont(m_hPushHotkey);

  y += 45;
  m_hChkImages =
      CreateWindowW(L"Button", L"Auto Copy Received Images",
                    WS_VISIBLE | WS_CHILD | BS_AUTOCHECKBOX, x + labelWidth, y,
                    inputWidth, h, m_hwnd, nullptr, hInst, nullptr);
  SetControlFont(m_hChkImages);
  y += 30;
  m_hChkFiles =
      CreateWindowW(L"Button", L"Auto Copy Received Files",
                    WS_VISIBLE | WS_CHILD | BS_AUTOCHECKBOX, x + labelWidth, y,
                    inputWidth, h, m_hwnd, nullptr, hInst, nullptr);
  SetControlFont(m_hChkFiles);
  y += 30;
  m_hChkStartup =
      CreateWindowW(L"Button", L"Start on Boot (Run at login)",
                    WS_VISIBLE | WS_CHILD | BS_AUTOCHECKBOX, x + labelWidth, y,
                    inputWidth, h, m_hwnd, nullptr, hInst, nullptr);
  SetControlFont(m_hChkStartup);

  y += 40;
  hCtrl = CreateWindowW(L"Static", L"Status:", WS_VISIBLE | WS_CHILD, x, y,
                        labelWidth, h, m_hwnd, nullptr, hInst, nullptr);
  SetControlFont(hCtrl);
  m_hStatus = CreateWindowW(L"Static", L"Disconnected", WS_VISIBLE | WS_CHILD,
                            x + labelWidth, y, inputWidth, h, m_hwnd, nullptr,
                            hInst, nullptr);
  SetControlFont(m_hStatus);

  y += 50;
  m_hBtnSave = CreateWindowW(L"Button", L"Save Settings",
                             WS_VISIBLE | WS_CHILD | BS_PUSHBUTTON, 150, y, 120,
                             35, m_hwnd, (HMENU)ID_BTN_SAVE, hInst, nullptr);
  SetControlFont(m_hBtnSave);
  m_hBtnCancel = CreateWindowW(
      L"Button", L"Cancel", WS_VISIBLE | WS_CHILD | BS_PUSHBUTTON, 290, y, 100,
      35, m_hwnd, (HMENU)ID_BTN_CANCEL, hInst, nullptr);
  SetControlFont(m_hBtnCancel);

  // Initial values
  auto &config = ConfigManager::Instance().GetConfig();
  SetWindowTextA(m_hServerUrl, config.server_url.c_str());
  SetWindowTextA(m_hDownloadPath, config.download_path.c_str());
  SetWindowTextA(m_hPushHotkey, config.push_hotkey.c_str());

  SendMessage(m_hChkImages, BM_SETCHECK,
              config.auto_copy_image ? BST_CHECKED : BST_UNCHECKED, 0);
  SendMessage(m_hChkFiles, BM_SETCHECK,
              config.auto_copy_file ? BST_CHECKED : BST_UNCHECKED, 0);
}

void MainWindow::RenderQRCode(HDC hdc) {
  auto &config = ConfigManager::Instance().GetConfig();
  std::string qrContent = nlohmann::json({{"server_url", config.server_url},
                                          {"room_key", config.room_key},
                                          {"device_id", config.device_id}})
                              .dump();

  QRcode *qr =
      QRcode_encodeString(qrContent.c_str(), 0, QR_ECLEVEL_L, QR_MODE_8, 1);
  if (qr) {
    Gdiplus::Graphics graphics(hdc);
    graphics.SetSmoothingMode(Gdiplus::SmoothingModeHighQuality);

    Gdiplus::SolidBrush blackBrush(Gdiplus::Color(255, 33, 33, 33));
    Gdiplus::SolidBrush whiteBrush(Gdiplus::Color(255, 255, 255, 255));

    // Move QR code to the right, far enough from inputs
    int x_off = 450, y_off = 40, size = 180;
    int cellSize = size / qr->width;
    if (cellSize < 1)
      cellSize = 1;

    // Draw label for QR Code
    Gdiplus::FontFamily fontFamily(L"Segoe UI");
    Gdiplus::Font font(&fontFamily, 10, Gdiplus::FontStyleRegular,
                       Gdiplus::UnitPoint);
    Gdiplus::PointF pointF(x_off, y_off - 25);
    graphics.DrawString(L"Scan to Pair Mobile", -1, &font, pointF, &blackBrush);

    graphics.FillRectangle(&whiteBrush, x_off, y_off, qr->width * cellSize,
                           qr->width * cellSize);

    for (int y = 0; y < qr->width; y++) {
      for (int x = 0; x < qr->width; x++) {
        if (qr->data[y * qr->width + x] & 1) {
          graphics.FillRectangle(&blackBrush, x_off + x * cellSize,
                                 y_off + y * cellSize, cellSize, cellSize);
        }
      }
    }
    QRcode_free(qr);
  }
}

void MainWindow::OnSave() {
  char buf[512];
  GetWindowTextA(m_hServerUrl, buf, 512);
  std::string url = buf;

  GetWindowTextA(m_hDownloadPath, buf, 512);
  std::string path = buf;

  GetWindowTextA(m_hPushHotkey, buf, 512);
  std::string hotkey = buf;

  auto &config = ConfigManager::Instance().GetConfig();
  config.server_url = url;
  config.download_path = path;
  config.push_hotkey = hotkey;
  config.auto_copy_image =
      SendMessage(m_hChkImages, BM_GETCHECK, 0, 0) == BST_CHECKED;
  config.auto_copy_file =
      SendMessage(m_hChkFiles, BM_GETCHECK, 0, 0) == BST_CHECKED;

  ConfigManager::Instance().Save();
  NetworkClient::Instance().Reconnect();

  // Update Hotkey
  // For simplicity, we assume format like "Alt+V"
  // In a real app we'd parse this properly.
  HotkeyManager::Instance().UnregisterAll();
  int mod = 0;
  if (hotkey.find("Alt") != std::string::npos)
    mod |= MOD_ALT;
  if (hotkey.find("Ctrl") != std::string::npos)
    mod |= MOD_CONTROL;
  if (hotkey.find("Shift") != std::string::npos)
    mod |= MOD_SHIFT;

  char key = 0;
  size_t plus = hotkey.find_last_of('+');
  if (plus != std::string::npos && plus + 1 < hotkey.length()) {
    key = hotkey[plus + 1];
  } else if (!hotkey.empty() && hotkey.find('+') == std::string::npos) {
    key = hotkey[0];
  }

  if (key >= 'a' && key <= 'z')
    key -= 32; // To upper

  if (key) {
    HotkeyManager::Instance().Register(mod, key, []() {
      spdlog::info("Push hotkey triggered!");
      // Trigger push logic here
    });
  }

  InvalidateRect(m_hwnd, nullptr, TRUE); // Update QR code
  ShowWindow(m_hwnd, SW_HIDE);
}

} // namespace ui
