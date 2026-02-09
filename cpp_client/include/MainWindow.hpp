#pragma once
#include "Common.hpp"
#include "TrayIcon.hpp"
#include "Window.hpp"
#include <gdiplus.h>
#pragma comment(lib, "gdiplus.lib")
#include <commctrl.h>
#include <qrencode.h>

namespace ui {

class MainWindow : public Window {
public:
  MainWindow();
  ~MainWindow();
  PCWSTR ClassName() const override { return L"ClipboardManMainWindow"; }
  LRESULT HandleMessage(UINT uMsg, WPARAM wParam, LPARAM lParam) override;

private:
  void CreateControls();
  void OnSave();
  void RenderQRCode(HDC hdc);

  std::unique_ptr<TrayIcon> m_trayIcon;

  HWND m_hServerUrl = nullptr;
  HWND m_hDownloadPath = nullptr;
  HWND m_hPushHotkey = nullptr;
  HWND m_hChkImages = nullptr;
  HWND m_hChkFiles = nullptr;
  HWND m_hChkStartup = nullptr;
  HWND m_hBtnSave = nullptr;
  HWND m_hBtnCancel = nullptr;
  HWND m_hStatus = nullptr;
};

} // namespace ui
