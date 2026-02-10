#include "TrayIcon.h"
#include "Resource.h"
#include <shellapi.h>

#define WM_TRAYICON (WM_USER + 1)

namespace ClipboardPush {
namespace UI {

TrayIcon& TrayIcon::Instance() {
    static TrayIcon instance;
    return instance;
}

bool TrayIcon::Init(HWND hWnd, HINSTANCE hInst) {
    ZeroMemory(&m_nid, sizeof(m_nid));
    m_nid.cbSize = sizeof(m_nid);
    m_nid.hWnd = hWnd;
    m_nid.uID = 1;
    m_nid.uFlags = NIF_ICON | NIF_MESSAGE | NIF_TIP | NIF_INFO;
    m_nid.uCallbackMessage = WM_TRAYICON;
    m_nid.hIcon = LoadIconW(hInst, MAKEINTRESOURCEW(IDI_APP_ICON));
    wcscpy_s(m_nid.szTip, L"Clipboard Push");

    m_hMenu = LoadMenuW(hInst, MAKEINTRESOURCEW(IDR_TRAY_MENU));

    return Shell_NotifyIconW(NIM_ADD, &m_nid);
}

void TrayIcon::Remove() {
    Shell_NotifyIconW(NIM_DELETE, &m_nid);
    if (m_hMenu) DestroyMenu(m_hMenu);
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
    SetForegroundWindow(hWnd);
    TrackPopupMenu(hSubMenu, TPM_RIGHTBUTTON, pt.x, pt.y, 0, hWnd, NULL);
}

}
}
