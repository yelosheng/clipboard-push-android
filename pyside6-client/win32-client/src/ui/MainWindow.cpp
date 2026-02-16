#include "MainWindow.h"
#include "Resource.h"
#include "SettingsWindow.h"
#include "core/Config.h"
#include "core/Utils.h"
#include "core/Logger.h"
#include "core/SyncLogic.h"
#include <commctrl.h>

#pragma comment(lib, "comctl32.lib")
#pragma comment(linker,"\"/manifestdependency:type='win32' name='Microsoft.Windows.Common-Controls' version='6.0.0.0' processorArchitecture='*' publicKeyToken='6595b64144ccf1df' language='*'\"")

namespace ClipboardPush {
namespace UI {

MainWindow& MainWindow::Instance() {
    static MainWindow instance;
    return instance;
}

// Subclass procedure for the Edit control
LRESULT CALLBACK EditSubclassProc(HWND hWnd, UINT uMsg, WPARAM wParam, LPARAM lParam, UINT_PTR uIdSubclass, DWORD_PTR dwRefData) {
    if (uMsg == WM_CHAR && wParam == 10) { // Ctrl+Enter produces LF (10)
        SendMessageW(GetParent(hWnd), WM_COMMAND, IDC_MAIN_PUSH, 0);
        return 0;
    }
    if (uMsg == WM_KEYDOWN && wParam == VK_RETURN && GetKeyState(VK_CONTROL) < 0) {
        SendMessageW(GetParent(hWnd), WM_COMMAND, IDC_MAIN_PUSH, 0);
        return 0;
    }
    return DefSubclassProc(hWnd, uMsg, wParam, lParam);
}

bool MainWindow::Create(HINSTANCE hInstance) {
    // Ensure common controls are initialized
    INITCOMMONCONTROLSEX icex;
    icex.dwSize = sizeof(INITCOMMONCONTROLSEX);
    icex.dwICC = ICC_WIN95_CLASSES;
    InitCommonControlsEx(&icex);

    m_hWnd = CreateDialogParamW(hInstance, MAKEINTRESOURCEW(IDD_MAINWINDOW), NULL, DialogProc, (LPARAM)this);
    if (m_hWnd) {
        HWND hEdit = GetDlgItem(m_hWnd, IDC_MAIN_TEXT);
        SetWindowSubclass(hEdit, EditSubclassProc, 0, 0);
    }
    return m_hWnd != NULL;
}

void MainWindow::Show(bool show) {
    ShowWindow(m_hWnd, show ? SW_SHOW : SW_HIDE);
    if (show) SetForegroundWindow(m_hWnd);
}

void MainWindow::SetStatus(const std::wstring& status) {
    // Current base title will be managed by UpdatePeerInfo or this fallback
    std::wstring title = L"Clipboard Push - " + status;
    SetWindowTextW(m_hWnd, title.c_str());
    
    // Also update hint text with current hotkey
    auto& config = Config::Instance().Data();
    std::wstring hint = L"Press " + Utils::ToWide(config.push_hotkey) + L" to push clipboard contents.";
    SetDlgItemTextW(m_hWnd, IDC_MAIN_HINT, hint.c_str());
}

void MainWindow::UpdatePeerInfo(const std::vector<std::string>& peerNames) {
    if (!m_hWnd) return;

    std::wstring title = L"Clipboard Push";
    bool canPush = !peerNames.empty();

    if (peerNames.empty()) {
        title += L" [Waiting for Peers...]";
    } else if (peerNames.size() == 1) {
        title += L" [Connected: " + Utils::ToWide(peerNames[0]) + L"]";
    } else {
        title += L" [Connected: " + std::to_wstring(peerNames.size()) + L" Peers]";
    }

    SetWindowTextW(m_hWnd, title.c_str());

    // Enable/Disable the Push button
    HWND hButton = GetDlgItem(m_hWnd, IDC_MAIN_PUSH);
    EnableWindow(hButton, canPush ? TRUE : FALSE);

    // Update Hint Text based on availability
    if (!canPush) {
        SetDlgItemTextW(m_hWnd, IDC_MAIN_HINT, L"Status: No peers connected. Connect your phone to enable pushing.");
    } else {
        auto& config = Config::Instance().Data();
        std::wstring hint = L"Ready to sync. Press " + Utils::ToWide(config.push_hotkey) + L" or use the button below.";
        SetDlgItemTextW(m_hWnd, IDC_MAIN_HINT, hint.c_str());
    }
}

INT_PTR CALLBACK MainWindow::DialogProc(HWND hDlg, UINT message, WPARAM wParam, LPARAM lParam) {
    switch (message) {
    case WM_INITDIALOG: {
        // Initial hint update
        MainWindow::Instance().SetStatus(L"Ready");

        // Add Tooltip for Push Button during initialization
        HWND hButton = GetDlgItem(hDlg, IDC_MAIN_PUSH);
        HWND hwndTip = CreateWindowExW(NULL, TOOLTIPS_CLASSW, NULL,
            WS_POPUP | TTS_ALWAYSTIP | TTS_BALLOON,
            CW_USEDEFAULT, CW_USEDEFAULT,
            CW_USEDEFAULT, CW_USEDEFAULT,
            hDlg, NULL, GetModuleHandle(NULL), NULL);

        if (hwndTip) {
            TOOLINFOW toolInfo = { 0 };
            toolInfo.cbSize = sizeof(toolInfo);
            toolInfo.hwnd = hDlg;
            toolInfo.uFlags = TTF_IDISHWND | TTF_SUBCLASS;
            toolInfo.uId = (UINT_PTR)hButton;
            toolInfo.lpszText = (LPWSTR)L"Ctrl+Enter to send";
            SendMessageW(hwndTip, TTM_ADDTOOLW, 0, (LPARAM)&toolInfo);
        }
        return (INT_PTR)TRUE;
    }

    case WM_COMMAND:
        switch (LOWORD(wParam)) {
        case IDC_MAIN_PUSH:
            {
                HWND hEdit = GetDlgItem(hDlg, IDC_MAIN_TEXT);
                int len = GetWindowTextLengthW(hEdit);
                if (len > 0) {
                    std::wstring wText(len + 1, 0);
                    GetWindowTextW(hEdit, &wText[0], len + 1);
                    wText.resize(len);
                    ClipboardPush::PushText(Utils::ToUtf8(wText));
                    SetDlgItemTextW(hDlg, IDC_MAIN_TEXT, L""); // Clear the box
                    MainWindow::Instance().SetStatus(L"Pushed Successfully");
                }
            }
            return (INT_PTR)TRUE;
        case IDC_MAIN_SETTINGS:
            SettingsWindow::Instance().Show();
            return (INT_PTR)TRUE;
        }
        break;

    case WM_CLOSE:
        ShowWindow(hDlg, SW_HIDE);
        return (INT_PTR)TRUE;
    }
    return (INT_PTR)FALSE;
}

}
}