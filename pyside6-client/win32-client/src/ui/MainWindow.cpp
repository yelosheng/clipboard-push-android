#include "MainWindow.h"
#include "Resource.h"
#include "SettingsWindow.h"
#include "core/Logger.h"

namespace ClipboardPush {
namespace UI {

MainWindow& MainWindow::Instance() {
    static MainWindow instance;
    return instance;
}

bool MainWindow::Create(HINSTANCE hInstance) {
    m_hWnd = CreateDialogParamW(hInstance, MAKEINTRESOURCEW(IDD_MAINWINDOW), NULL, DialogProc, (LPARAM)this);
    return m_hWnd != NULL;
}

void MainWindow::Show(bool show) {
    ShowWindow(m_hWnd, show ? SW_SHOW : SW_HIDE);
    if (show) SetForegroundWindow(m_hWnd);
}

void MainWindow::SetStatus(const std::wstring& status) {
    SetDlgItemTextW(m_hWnd, IDC_MAIN_STATUS, (L"Status: " + status).c_str());
}

INT_PTR CALLBACK MainWindow::DialogProc(HWND hDlg, UINT message, WPARAM wParam, LPARAM lParam) {
    switch (message) {
    case WM_INITDIALOG:
        return (INT_PTR)TRUE;

    case WM_COMMAND:
        switch (LOWORD(wParam)) {
        case IDC_MAIN_PUSH:
            LOG_INFO("Push Button Clicked");
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
