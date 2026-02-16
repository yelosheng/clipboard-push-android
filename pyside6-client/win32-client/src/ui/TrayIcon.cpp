#include "TrayIcon.h"
#include "Resource.h"
#include "core/Config.h"
#include <shellapi.h>

#define WM_TRAYICON (WM_USER + 1)

namespace ClipboardPush {
namespace UI {

TrayIcon& TrayIcon::Instance() {
    static TrayIcon instance;
    return instance;
}

bool TrayIcon::Init(HWND hWnd, HINSTANCE hInst) {
    m_hInst = hInst;
    ZeroMemory(&m_nid, sizeof(m_nid));
    m_nid.cbSize = sizeof(m_nid);
    m_nid.hWnd = hWnd;
    m_nid.uID = 1;
    m_nid.uFlags = NIF_ICON | NIF_MESSAGE | NIF_TIP | NIF_INFO;
    m_nid.uCallbackMessage = WM_TRAYICON;
    m_hCurrentIcon = LoadIconW(hInst, MAKEINTRESOURCEW(IDI_APP_ICON));
    m_nid.hIcon = m_hCurrentIcon;
    wcscpy_s(m_nid.szTip, L"Clipboard Push");

    m_hMenu = LoadMenuW(hInst, MAKEINTRESOURCEW(IDR_TRAY_MENU));

    return Shell_NotifyIconW(NIM_ADD, &m_nid);
}

void TrayIcon::Remove() {
    Shell_NotifyIconW(NIM_DELETE, &m_nid);
    if (m_hMenu) DestroyMenu(m_hMenu);
    if (m_hCurrentIcon) DestroyIcon(m_hCurrentIcon);
}

void TrayIcon::UpdateIcon(HICON hNewIcon) {
    if (!hNewIcon) return;
    
    // Destroy previous icon ONLY if it was dynamically created (or if we want to replace the default)
    // Actually, always destroy the previous handle we tracked to prevent leaks
    if (m_hCurrentIcon) DestroyIcon(m_hCurrentIcon);
    
    m_hCurrentIcon = hNewIcon;
    m_nid.hIcon = m_hCurrentIcon;
    m_nid.uFlags = NIF_ICON;
    Shell_NotifyIconW(NIM_MODIFY, &m_nid);
}

void TrayIcon::ShowMessage(const std::wstring& title, const std::wstring& msg) {
    m_nid.uFlags |= NIF_INFO;
    wcscpy_s(m_nid.szInfoTitle, title.c_str());
    wcscpy_s(m_nid.szInfo, msg.c_str());
    m_nid.dwInfoFlags = NIIF_INFO;
    Shell_NotifyIconW(NIM_MODIFY, &m_nid);
}

void TrayIcon::ShowContextMenu(HWND hWnd) {
    POINT pt;
    GetCursorPos(&pt);
    HMENU hSubMenu = GetSubMenu(m_hMenu, 0);
    
    // Update check states before showing
    auto& data = Config::Instance().Data();
    CheckMenuItem(hSubMenu, IDM_TRAY_AUTO_PUSH_TEXT, MF_BYCOMMAND | (data.auto_push_text ? MF_CHECKED : MF_UNCHECKED));
    CheckMenuItem(hSubMenu, IDM_TRAY_AUTO_PUSH_IMG, MF_BYCOMMAND | (data.auto_push_image ? MF_CHECKED : MF_UNCHECKED));
    CheckMenuItem(hSubMenu, IDM_TRAY_AUTO_PUSH_FILE, MF_BYCOMMAND | (data.auto_push_file ? MF_CHECKED : MF_UNCHECKED));
    CheckMenuItem(hSubMenu, IDM_TRAY_AUTO_COPY_IMG, MF_BYCOMMAND | (data.auto_copy_image ? MF_CHECKED : MF_UNCHECKED));
    CheckMenuItem(hSubMenu, IDM_TRAY_AUTO_COPY_FILE, MF_BYCOMMAND | (data.auto_copy_file ? MF_CHECKED : MF_UNCHECKED));
    CheckMenuItem(hSubMenu, IDM_TRAY_AUTO_START, MF_BYCOMMAND | (data.auto_start ? MF_CHECKED : MF_UNCHECKED));
    CheckMenuItem(hSubMenu, IDM_TRAY_NOTIFICATIONS, MF_BYCOMMAND | (data.show_notifications ? MF_CHECKED : MF_UNCHECKED));

    // Disable Push if no active peers
    EnableMenuItem(hSubMenu, IDM_TRAY_PUSH, MF_BYCOMMAND | (m_hasPeers ? MF_ENABLED : MF_GRAYED));

    SetForegroundWindow(hWnd);
    TrackPopupMenu(hSubMenu, TPM_RIGHTBUTTON, pt.x, pt.y, 0, hWnd, NULL);
}

}
}
