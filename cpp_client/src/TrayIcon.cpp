#include "TrayIcon.hpp"
#include "Common.hpp"


namespace ui {

TrayIcon::TrayIcon(HWND hWnd, UINT uID, UINT uCallbackMsg, HICON hIcon,
                   const std::wstring &tip) {
  ZeroMemory(&m_nid, sizeof(m_nid));
  m_nid.cbSize = sizeof(m_nid);
  m_nid.hWnd = hWnd;
  m_nid.uID = uID;
  m_nid.uFlags = NIF_ICON | NIF_MESSAGE | NIF_TIP | NIF_SHOWTIP;
  m_nid.uCallbackMessage = uCallbackMsg;
  m_nid.hIcon = hIcon;
  wcsncpy_s(m_nid.szTip, tip.c_str(), _countof(m_nid.szTip));
  m_nid.uVersion = NOTIFYICON_VERSION_4;
}

TrayIcon::~TrayIcon() { Remove(); }

bool TrayIcon::Add() {
  if (m_added)
    return true;
  if (Shell_NotifyIconW(NIM_ADD, &m_nid)) {
    Shell_NotifyIconW(NIM_SETVERSION, &m_nid);
    m_added = true;
    return true;
  }
  return false;
}

bool TrayIcon::Remove() {
  if (!m_added)
    return true;
  if (Shell_NotifyIconW(NIM_DELETE, &m_nid)) {
    m_added = false;
    return true;
  }
  return false;
}

bool TrayIcon::ShowBalloon(const std::wstring &title, const std::wstring &text,
                           DWORD dwInfoFlags) {
  NOTIFYICONDATAW nid = m_nid;
  nid.uFlags |= NIF_INFO;
  wcsncpy_s(nid.szInfoTitle, title.c_str(), _countof(nid.szInfoTitle));
  wcsncpy_s(nid.szInfo, text.c_str(), _countof(nid.szInfo));
  nid.dwInfoFlags = dwInfoFlags;
  return Shell_NotifyIconW(NIM_MODIFY, &nid);
}

} // namespace ui
