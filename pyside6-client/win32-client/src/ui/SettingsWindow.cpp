#include "SettingsWindow.h"
#include "Resource.h"
#include "core/Config.h"
#include "core/Utils.h"
#include "core/Logger.h"
#include "qrcodegen.hpp"
#include <shlobj.h>

namespace ClipboardPush {
namespace UI {

SettingsWindow& SettingsWindow::Instance() {
    static SettingsWindow instance;
    return instance;
}

bool SettingsWindow::Create(HINSTANCE hInstance) {
    m_hWnd = CreateDialogParamW(hInstance, MAKEINTRESOURCEW(IDD_SETTINGSWINDOW), NULL, DialogProc, (LPARAM)this);
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
    
    CheckDlgButton(m_hWnd, IDC_SETTINGS_IMAGES, data.auto_copy_image ? BST_CHECKED : BST_UNCHECKED);
    CheckDlgButton(m_hWnd, IDC_SETTINGS_FILES, data.auto_copy_file ? BST_CHECKED : BST_UNCHECKED);
    CheckDlgButton(m_hWnd, IDC_SETTINGS_STARTUP, data.auto_start ? BST_CHECKED : BST_UNCHECKED);
    CheckDlgButton(m_hWnd, IDC_SETTINGS_MINIMIZED, data.start_minimized ? BST_CHECKED : BST_UNCHECKED);

    // Update QR - using the requested JSON format
    nlohmann::json j;
    j["key"] = data.room_key;
    j["room"] = data.room_id;
    j["server"] = data.relay_server_url;
    UpdateQR(j.dump());
}

void SettingsWindow::SaveSettings() {
    auto& data = Config::Instance().Data();
    
    wchar_t buffer[MAX_PATH];
    GetDlgItemTextW(m_hWnd, IDC_SETTINGS_PATH, buffer, MAX_PATH);
    data.download_path = Utils::ToUtf8(buffer);
    
    GetDlgItemTextW(m_hWnd, IDC_SETTINGS_HOTKEY, buffer, MAX_PATH);
    data.push_hotkey = Utils::ToUtf8(buffer);
    
    data.auto_copy_image = (IsDlgButtonChecked(m_hWnd, IDC_SETTINGS_IMAGES) == BST_CHECKED);
    data.auto_copy_file = (IsDlgButtonChecked(m_hWnd, IDC_SETTINGS_FILES) == BST_CHECKED);
    data.auto_start = (IsDlgButtonChecked(m_hWnd, IDC_SETTINGS_STARTUP) == BST_CHECKED);
    data.start_minimized = (IsDlgButtonChecked(m_hWnd, IDC_SETTINGS_MINIMIZED) == BST_CHECKED);
    
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
            return (INT_PTR)TRUE;
        case IDC_SETTINGS_RECONNECT:
            LOG_INFO("Reconnect Button Clicked");
            // Logic for reconnection will be implemented in Phase 6
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
