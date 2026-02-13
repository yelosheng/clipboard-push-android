#include "NotificationWindow.h"
#include "Resource.h"
#include "core/Logger.h"
#include <gdiplus.h>
#include <shellapi.h>
#include <algorithm>

namespace ClipboardPush {
namespace UI {

// GDI+ Helpers for rounded rectangles
void GetRoundedRectPath(Gdiplus::GraphicsPath& path, Gdiplus::RectF rect, float radius) {
    float dia = radius * 2.0f;
    path.AddArc(rect.X, rect.Y, dia, dia, 180, 90);
    path.AddArc(rect.X + rect.Width - dia, rect.Y, dia, dia, 270, 90);
    path.AddArc(rect.X + rect.Width - dia, rect.Y + rect.Height - dia, dia, dia, 0, 90);
    path.AddArc(rect.X, rect.Y + rect.Height - dia, dia, dia, 90, 90);
    path.CloseFigure();
}

void FillRoundedRectangle(Gdiplus::Graphics* g, Gdiplus::Brush* brush, Gdiplus::RectF rect, float radius) {
    Gdiplus::GraphicsPath path;
    GetRoundedRectPath(path, rect, radius);
    g->FillPath(brush, &path);
}

void DrawRoundedRectangle(Gdiplus::Graphics* g, Gdiplus::Pen* pen, Gdiplus::RectF rect, float radius) {
    Gdiplus::GraphicsPath path;
    GetRoundedRectPath(path, rect, radius);
    g->DrawPath(pen, &path);
}

std::vector<HWND> NotificationWindow::s_activeNotifications;

void NotificationWindow::RegisterClass(HINSTANCE hInst) {
    const wchar_t CLASS_NAME[] = L"ClipboardPushNotification";
    WNDCLASSW wc = {};
    wc.lpfnWndProc = WndProc;
    wc.hInstance = hInst;
    wc.lpszClassName = CLASS_NAME;
    wc.hCursor = LoadCursor(NULL, IDC_ARROW);
    ::RegisterClassW(&wc);
}

void NotificationWindow::HandleMessage(LPARAM lParam) {
    NotificationData* data = reinterpret_cast<NotificationData*>(lParam);
    if (data) {
        Show(data->title, data->message, data->style);
        delete data;
    }
}

void NotificationWindow::Show(const std::wstring& title, const std::wstring& message, NotificationStyle style) {
    for (HWND old : s_activeNotifications) {
        if (IsWindow(old)) DestroyWindow(old);
    }
    s_activeNotifications.clear();

    new NotificationWindow(title, message, style);
}

NotificationWindow::NotificationWindow(const std::wstring& title, const std::wstring& message, NotificationStyle style)
    : m_title(title), m_message(message), m_style(style) {
    
    HINSTANCE hInst = GetModuleHandle(NULL);
    const wchar_t CLASS_NAME[] = L"ClipboardPushNotification";

    m_hWnd = CreateWindowExW(
        WS_EX_LAYERED | WS_EX_TOPMOST | WS_EX_TOOLWINDOW,
        CLASS_NAME, L"", WS_POPUP,
        0, 0, 300, 80, NULL, NULL, hInst, this
    );

    if (m_hWnd) {
        s_activeNotifications.push_back(m_hWnd);
        PositionWindow();
        SetTimer(m_hWnd, 1, 16, NULL); // 60 FPS
        m_startTime = GetTickCount();
        ShowWindow(m_hWnd, SW_SHOWNOACTIVATE);
        Render();
    }
}

NotificationWindow::~NotificationWindow() {
    auto it = std::find(s_activeNotifications.begin(), s_activeNotifications.end(), m_hWnd);
    if (it != s_activeNotifications.end()) s_activeNotifications.erase(it);
}

void NotificationWindow::PositionWindow() {
    RECT workArea;
    SystemParametersInfoW(SPI_GETWORKAREA, 0, &workArea, 0);
    
    int width = 300;
    int height = 80;
    int x = workArea.right - width - 20;
    m_targetY = workArea.bottom - height - 20;
    m_yPos = m_targetY + 20; // Start lower for slide-in

    SetWindowPos(m_hWnd, HWND_TOPMOST, x, m_yPos, width, height, SWP_NOACTIVATE);
}

void NotificationWindow::Render() {
    RECT rect;
    GetWindowRect(m_hWnd, &rect);
    int width = rect.right - rect.left;
    int height = rect.bottom - rect.top;

    HDC hdcScreen = GetDC(NULL);
    HDC hdcMem = CreateCompatibleDC(hdcScreen);
    HBITMAP hBitmap = CreateCompatibleBitmap(hdcScreen, width, height);
    SelectObject(hdcMem, hBitmap);

    {
        Gdiplus::Graphics g(hdcMem);
        g.SetSmoothingMode(Gdiplus::SmoothingModeAntiAlias);
        g.SetTextRenderingHint(Gdiplus::TextRenderingHintAntiAlias);

        g.Clear(Gdiplus::Color(0, 0, 0, 0));

        // Draw shadow
        Gdiplus::SolidBrush shadowBrush(Gdiplus::Color(30, 0, 0, 0));
        FillRoundedRectangle(&g, &shadowBrush, Gdiplus::RectF(2, 2, (float)width - 4, (float)height - 4), 12.0f);

        // Draw bubble background
        Gdiplus::SolidBrush bgBrush(Gdiplus::Color(255, 255, 255, 255));
        FillRoundedRectangle(&g, &bgBrush, Gdiplus::RectF(0, 0, (float)width - 5, (float)height - 5), 10.0f);

        // Header Color based on style
        Gdiplus::Color headerColor = (m_style == NotificationStyle::Inbound) 
            ? Gdiplus::Color(255, 0, 136, 204)  // Telegram Blue
            : Gdiplus::Color(255, 76, 175, 80); // Success Green

        // Draw border
        Gdiplus::Pen borderPen(headerColor, 1.0f);
        DrawRoundedRectangle(&g, &borderPen, Gdiplus::RectF(0, 0, (float)width - 5, (float)height - 5), 10.0f);

        // Fonts
        Gdiplus::Font titleFont(L"Segoe UI", 10, Gdiplus::FontStyleBold);
        Gdiplus::Font msgFont(L"Segoe UI", 9);
        Gdiplus::SolidBrush titleBrush(headerColor);
        Gdiplus::SolidBrush msgBrush(Gdiplus::Color(255, 50, 50, 50));

        g.DrawString(m_title.c_str(), -1, &titleFont, Gdiplus::PointF(15, 12), &titleBrush);
        
        std::wstring displayMsg = m_message;
        if (displayMsg.length() > 60) displayMsg = displayMsg.substr(0, 57) + L"...";
        g.DrawString(displayMsg.c_str(), -1, &msgFont, Gdiplus::PointF(15, 35), &msgBrush);
    }

    POINT ptSrc = { 0, 0 };
    SIZE sizeWnd = { width, height };
    BLENDFUNCTION blend = { AC_SRC_OVER, 0, (BYTE)m_alpha, AC_SRC_ALPHA };
    POINT ptDest = { rect.left, m_yPos };

    UpdateLayeredWindow(m_hWnd, hdcScreen, &ptDest, &sizeWnd, hdcMem, &ptSrc, 0, &blend, ULW_ALPHA);

    DeleteObject(hBitmap);
    DeleteDC(hdcMem);
    ReleaseDC(NULL, hdcScreen);
}

void NotificationWindow::Animate() {
    if (m_state == 0) { // Fade In
        m_alpha = std::min(255, m_alpha + 25);
        if (m_yPos > m_targetY) m_yPos -= 2;
        if (m_alpha >= 255 && m_yPos <= m_targetY) {
            m_state = 1;
            m_startTime = GetTickCount();
        }
    } else if (m_state == 1) { // Stay
        if (GetTickCount() - m_startTime > 3000) m_state = 2;
    } else if (m_state == 2) { // Fade Out
        m_alpha = std::max(0, m_alpha - 20);
        if (m_alpha <= 0) {
            DestroyWindow(m_hWnd);
            return;
        }
    }
    Render();
}

LRESULT CALLBACK NotificationWindow::WndProc(HWND hWnd, UINT message, WPARAM wParam, LPARAM lParam) {
    NotificationWindow* pThis = nullptr;
    if (message == WM_NCCREATE) {
        pThis = static_cast<NotificationWindow*>((reinterpret_cast<LPCREATESTRUCT>(lParam))->lpCreateParams);
        SetWindowLongPtr(hWnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(pThis));
    } else {
        pThis = reinterpret_cast<NotificationWindow*>(GetWindowLongPtr(hWnd, GWLP_USERDATA));
    }

    switch (message) {
    case WM_TIMER:
        if (pThis) pThis->Animate();
        break;
    case WM_LBUTTONDOWN:
        if (pThis) pThis->m_state = 2;
        break;
    case WM_DESTROY:
        delete pThis;
        SetWindowLongPtr(hWnd, GWLP_USERDATA, 0);
        return 0;
    }
    return DefWindowProc(hWnd, message, wParam, lParam);
}

}
}
