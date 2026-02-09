#pragma once
#ifndef _WIN32_IE
#define _WIN32_IE 0x0600
#endif
#include "Common.hpp"
#include <shellapi.h>

namespace ui {

class TrayIcon {
public:
  TrayIcon(HWND hWnd, UINT uID, UINT uCallbackMsg, HICON hIcon,
           const std::wstring &tip);
  ~TrayIcon();

  bool Add();
  bool Remove();
  bool ShowBalloon(const std::wstring &title, const std::wstring &text,
                   DWORD dwInfoFlags = NIIF_INFO);

private:
  NOTIFYICONDATAW m_nid;
  bool m_added = false;
};

} // namespace ui
