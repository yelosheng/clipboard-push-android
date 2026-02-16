#pragma once
#include <windows.h>
#include <shellapi.h>
#include <string>

namespace ClipboardPush {
namespace UI {

class TrayIcon {
public:
    static TrayIcon& Instance();

    bool Init(HWND hWnd, HINSTANCE hInst);
    void Remove();
    
    void ShowMessage(const std::wstring& title, const std::wstring& msg);
    void ShowContextMenu(HWND hWnd);
    void UpdateIcon(HICON hNewIcon);
    void SetPeerState(bool hasPeers) { m_hasPeers = hasPeers; }

private:
    TrayIcon() = default;
    NOTIFYICONDATAW m_nid;
    HMENU m_hMenu = NULL;
    HICON m_hCurrentIcon = NULL;
    HINSTANCE m_hInst = NULL;
    bool m_hasPeers = false;
};

}
}
