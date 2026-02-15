#include "SettingsWindow.h"
#include "Resource.h"
#include "core/Config.h"
#include "core/Utils.h"
#include "core/Logger.h"
#include "core/LocalServer.h"
#include "qrcodegen.hpp"
#include <shlobj.h>
#include <commctrl.h>
#include <vector>
#include <string>

#pragma comment(lib, "comctl32.lib")

namespace ClipboardPush {
namespace UI {

// Helper to get key name
std::wstring GetKeyName(UINT vk) {
    if (vk >= 'A' && vk <= 'Z') return std::wstring(1, (wchar_t)vk);
    if (vk >= VK_F1 && vk <= VK_F12) return L"F" + std::to_wstring(vk - VK_F1 + 1);
    // Add more common keys if needed
    switch(vk) {
        case VK_SPACE: return L"Space";
        case VK_INSERT: return L"Insert";
        case VK_DELETE: return L"Delete";
        case VK_HOME: return L"Home";
        case VK_END: return L"End";
    }
    return L"";
}

// Subclass procedure for Hotkey Edit control
LRESULT CALLBACK HotkeyEditSubclassProc(HWND hWnd, UINT uMsg, WPARAM wParam, LPARAM lParam, UINT_PTR uIdSubclass, DWORD_PTR dwRefData) {
    static bool isRecording = false;

    switch (uMsg) {
    case WM_SETFOCUS:
        isRecording = true;
        SetWindowTextW(hWnd, L"Press keys...");
        return 0;

    case WM_KILLFOCUS:
        isRecording = false;
        if (GetWindowTextLengthW(hWnd) == 0 || GetWindowTextLengthW(hWnd) > 20) {
            // Restore from config if empty
            SetWindowTextW(hWnd, Utils::ToWide(Config::Instance().Data().push_hotkey).c_str());
        }
        return 0;

    case WM_SYSKEYDOWN:
    case WM_KEYDOWN: {
        if (!isRecording) break;

        UINT vk = (UINT)wParam;
        // Ignore standalone modifiers
        if (vk == VK_CONTROL || vk == VK_SHIFT || vk == VK_MENU || vk == VK_LWIN || vk == VK_RWIN) {
            return 0;
        }

        std::wstring hotkey;
        if (GetKeyState(VK_CONTROL) < 0) hotkey += L"Ctrl+";
        if (GetKeyState(VK_MENU) < 0) hotkey += L"Alt+";
        if (GetKeyState(VK_SHIFT) < 0) hotkey += L"Shift+";
        if (GetKeyState(VK_LWIN) < 0 || GetKeyState(VK_RWIN) < 0) hotkey += L"Win+";

        std::wstring keyName = GetKeyName(vk);
        if (!keyName.empty()) {
            hotkey += keyName;
            SetWindowTextW(hWnd, hotkey.c_str());
            // Move focus away to finish recording
            SetFocus(GetParent(hWnd));
        }
        return 0;
    }
    case WM_CHAR:
        return 0; // Disable character input
    }
    return DefSubclassProc(hWnd, uMsg, wParam, lParam);
}

SettingsWindow& SettingsWindow::Instance() {
    static SettingsWindow instance;
    return instance;
}

bool SettingsWindow::Create(HINSTANCE hInstance, HWND hParent) {
    m_hParent = hParent;
    m_hWnd = CreateDialogParamW(hInstance, MAKEINTRESOURCEW(IDD_SETTINGSWINDOW), hParent, DialogProc, (LPARAM)this);
    if (m_hWnd) {
        HWND hHotkey = GetDlgItem(m_hWnd, IDC_SETTINGS_HOTKEY);
        SetWindowSubclass(hHotkey, HotkeyEditSubclassProc, 0, 0);
    }
    return m_hWnd != NULL;
}

void SettingsWindow::Show(bool show) {
    if (show) LoadSettings();
    ShowWindow(m_hWnd, show ? SW_SHOW : SW_HIDE);
    if (show) SetForegroundWindow(m_hWnd);
}

void SettingsWindow::LoadSettings() {
    auto& data = Config::Instance().Data();
    SetDlgItemTextW(m_hWnd, IDC_SETTINGS_PATH, Utils::ToWide(data.download_path).c_str());
    SetDlgItemTextW(m_hWnd, IDC_SETTINGS_HOTKEY, Utils::ToWide(data.push_hotkey).c_str());
    SetDlgItemInt(m_hWnd, IDC_SETTINGS_LAN_TIMEOUT, data.lan_timeout, FALSE);
    SetDlgItemTextW(m_hWnd, IDC_SETTINGS_DEVICEID, Utils::ToWide(data.device_id).c_str());
    
    CheckDlgButton(m_hWnd, IDC_SETTINGS_IMAGES, data.auto_copy_image ? BST_CHECKED : BST_UNCHECKED);
    CheckDlgButton(m_hWnd, IDC_SETTINGS_FILES, data.auto_copy_file ? BST_CHECKED : BST_UNCHECKED);
    CheckDlgButton(m_hWnd, IDC_SETTINGS_PUSH_TEXT, data.auto_push_text ? BST_CHECKED : BST_UNCHECKED);
    CheckDlgButton(m_hWnd, IDC_SETTINGS_PUSH_IMAGE, data.auto_push_image ? BST_CHECKED : BST_UNCHECKED);
    CheckDlgButton(m_hWnd, IDC_SETTINGS_PUSH_FILE, data.auto_push_file ? BST_CHECKED : BST_UNCHECKED);
    CheckDlgButton(m_hWnd, IDC_SETTINGS_STARTUP, data.auto_start ? BST_CHECKED : BST_UNCHECKED);
    CheckDlgButton(m_hWnd, IDC_SETTINGS_MINIMIZED, data.start_minimized ? BST_CHECKED : BST_UNCHECKED);
    CheckDlgButton(m_hWnd, IDC_SETTINGS_NOTIFICATIONS, data.show_notifications ? BST_CHECKED : BST_UNCHECKED);

    // Update QR - using the requested JSON format
    nlohmann::json j;
    j["key"] = data.room_key;
    j["room"] = data.room_id;
    j["server"] = data.relay_server_url;
    j["local_ip"] = LocalServer::Instance().GetIP();
    j["local_port"] = LocalServer::Instance().GetPort();
    UpdateQR(j.dump());
}

void SettingsWindow::SaveSettings() {
    auto& data = Config::Instance().Data();
    
    wchar_t buffer[MAX_PATH];
    GetDlgItemTextW(m_hWnd, IDC_SETTINGS_PATH, buffer, MAX_PATH);
    data.download_path = Utils::ToUtf8(buffer);
    
    GetDlgItemTextW(m_hWnd, IDC_SETTINGS_HOTKEY, buffer, MAX_PATH);
    data.push_hotkey = Utils::ToUtf8(buffer);

    data.lan_timeout = GetDlgItemInt(m_hWnd, IDC_SETTINGS_LAN_TIMEOUT, NULL, FALSE);
    if (data.lan_timeout <= 0) data.lan_timeout = 10;

    GetDlgItemTextW(m_hWnd, IDC_SETTINGS_DEVICEID, buffer, MAX_PATH);
    data.device_id = Utils::ToUtf8(buffer);
    
    data.auto_copy_image = (IsDlgButtonChecked(m_hWnd, IDC_SETTINGS_IMAGES) == BST_CHECKED);
    data.auto_copy_file = (IsDlgButtonChecked(m_hWnd, IDC_SETTINGS_FILES) == BST_CHECKED);
    data.auto_push_text = (IsDlgButtonChecked(m_hWnd, IDC_SETTINGS_PUSH_TEXT) == BST_CHECKED);
    data.auto_push_image = (IsDlgButtonChecked(m_hWnd, IDC_SETTINGS_PUSH_IMAGE) == BST_CHECKED);
    data.auto_push_file = (IsDlgButtonChecked(m_hWnd, IDC_SETTINGS_PUSH_FILE) == BST_CHECKED);
    data.auto_start = (IsDlgButtonChecked(m_hWnd, IDC_SETTINGS_STARTUP) == BST_CHECKED);
    data.start_minimized = (IsDlgButtonChecked(m_hWnd, IDC_SETTINGS_MINIMIZED) == BST_CHECKED);
    data.show_notifications = (IsDlgButtonChecked(m_hWnd, IDC_SETTINGS_NOTIFICATIONS) == BST_CHECKED);
    
    Config::Instance().Save();
    LOG_INFO("Settings saved");

    // Signal main logic to update
    SendMessageW(GetWindow(m_hWnd, GW_OWNER), WM_COMMAND, IDC_SETTINGS_SAVE, 0);
}

void SettingsWindow::UpdateQR(const std::string& content) {
    using qrcodegen::QrCode;
    try {
        QrCode qr = QrCode::encodeText(content.c_str(), QrCode::Ecc::LOW);
        m_qrSize = qr.getSize();
        m_qrModules.clear();
        for (int y = 0; y < m_qrSize; y++) {
            for (int x = 0; x < m_qrSize; x++) {
                m_qrModules.push_back(qr.getModule(x, y));
            }
        }
        InvalidateRect(GetDlgItem(m_hWnd, IDC_SETTINGS_QR), NULL, TRUE);
    } catch (...) {}
}

INT_PTR CALLBACK SettingsWindow::DialogProc(HWND hDlg, UINT message, WPARAM wParam, LPARAM lParam) {
    SettingsWindow* pThis = (SettingsWindow*)GetWindowLongPtr(hDlg, DWLP_USER);

    switch (message) {
    case WM_INITDIALOG:
        SetWindowLongPtr(hDlg, DWLP_USER, (LONG_PTR)lParam);
        return (INT_PTR)TRUE;

    case WM_DRAWITEM: {
        LPDRAWITEMSTRUCT lpDrawItem = (LPDRAWITEMSTRUCT)lParam;
        if (lpDrawItem->CtlID == IDC_SETTINGS_QR && pThis && pThis->m_qrSize > 0) {
            HDC hdc = lpDrawItem->hDC;
            RECT rect = lpDrawItem->rcItem;
            FillRect(hdc, &rect, (HBRUSH)GetStockObject(WHITE_BRUSH));
            
            int cellSize = (rect.right - rect.left) / pThis->m_qrSize;
            int offset = ((rect.right - rect.left) % pThis->m_qrSize) / 2;
            
            HBRUSH blackBrush = (HBRUSH)GetStockObject(BLACK_BRUSH);
            for (int y = 0; y < pThis->m_qrSize; y++) {
                for (int x = 0; x < pThis->m_qrSize; x++) {
                    if (pThis->m_qrModules[y * pThis->m_qrSize + x]) {
                        RECT r;
                        r.left = rect.left + offset + x * cellSize;
                        r.top = rect.top + offset + y * cellSize;
                        r.right = r.left + cellSize;
                        r.bottom = r.top + cellSize;
                        FillRect(hdc, &r, blackBrush);
                    }
                }
            }
            return (INT_PTR)TRUE;
        }
        break;
    }

    case WM_COMMAND:
        switch (LOWORD(wParam)) {
        case IDC_SETTINGS_SAVE:
            pThis->SaveSettings();
            ShowWindow(hDlg, SW_HIDE);
            SendMessageW(pThis->m_hParent, WM_COMMAND, IDC_SETTINGS_SAVE, 0);
            return (INT_PTR)TRUE;
        case IDC_SETTINGS_RECONNECT:
            LOG_INFO("Reconnect Button Clicked");
            SendMessageW(pThis->m_hParent, WM_COMMAND, IDC_SETTINGS_RECONNECT, 0);
            return (INT_PTR)TRUE;
        case IDC_SETTINGS_RESET:
            if (MessageBoxW(hDlg, L"This will generate a NEW Room ID and Key.\nYou will need to re-scan the QR code on all devices.\n\nContinue?", L"Reset Connection", MB_YESNO | MB_ICONWARNING) == IDYES) {
                Config::Instance().GenerateNewCredentials();
                pThis->LoadSettings(); // Refresh UI and QR
                // Force reconnect with new credentials
                SendMessageW(pThis->m_hParent, WM_COMMAND, IDC_SETTINGS_SAVE, 0);
            }
            return (INT_PTR)TRUE;
        case IDC_SETTINGS_BROWSE: {
            BROWSEINFOW bi = { 0 };
            bi.lpszTitle = L"Select Download Folder";
            bi.ulFlags = BIF_RETURNONLYFSDIRS | BIF_NEWDIALOGSTYLE;
            LPITEMIDLIST pidl = SHBrowseForFolderW(&bi);
            if (pidl != NULL) {
                wchar_t path[MAX_PATH];
                SHGetPathFromIDListW(pidl, path);
                SetDlgItemTextW(hDlg, IDC_SETTINGS_PATH, path);
                CoTaskMemFree(pidl);
            }
            return (INT_PTR)TRUE;
        }
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
