#pragma once
#include <windows.h>
#include <string>

namespace ClipboardPush {
namespace UI {

class MainWindow {
public:
    static MainWindow& Instance();

    bool Create(HINSTANCE hInstance);
    void Show(bool show = true);
    void SetStatus(const std::wstring& status);
    HWND GetHWND() const { return m_hWnd; }

private:
    MainWindow() = default;
    static INT_PTR CALLBACK DialogProc(HWND hDlg, UINT message, WPARAM wParam, LPARAM lParam);

    HWND m_hWnd = NULL;
};

}
}
