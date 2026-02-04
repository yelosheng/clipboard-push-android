#include "Gui.hpp"
#include <commctrl.h>
#include <gdiplus.h>
#include <spdlog/spdlog.h>
#include "Clipboard.hpp"

// 修正 Manifest Pragma
#pragma comment(linker,"\"/manifestdependency:type='win32' name='Microsoft.Windows.Common-Controls' version='6.0.0.0' processorArchitecture='*' publicKeyToken='6595b64144ccf1df' language='*'\"")

#define IDM_PUSH 1001
#define IDM_SETTINGS 1002
#define IDM_EXIT 1003
#define ID_BTN_SAVE 2001
#define ID_BTN_CANCEL 2002

Gui* g_Gui = nullptr;

Gui::Gui(HINSTANCE hInstance, AppConfig& config, std::function<void()> onPush, std::function<void()> onReloadConfig)
    : m_hInstance(hInstance), m_config(config), m_onPush(onPush), m_onReloadConfig(onReloadConfig) {
    g_Gui = this;
}

Gui::~Gui() {
    RemoveTrayIcon();
}

bool Gui::Init() {
    // 确保 Common Controls 已初始化 (用于 Hotkey 控件)
    INITCOMMONCONTROLSEX icex;
    icex.dwSize = sizeof(INITCOMMONCONTROLSEX);
    icex.dwICC = ICC_HOTKEY_CLASS;
    InitCommonControlsEx(&icex);

    WNDCLASSEXW wc = {0};
    wc.cbSize = sizeof(WNDCLASSEXW);
    wc.lpfnWndProc = WndProc;
    wc.hInstance = m_hInstance;
    wc.hbrBackground = (HBRUSH)(COLOR_WINDOW);
    wc.lpszClassName = L"ClipboardManClass";
    if (!RegisterClassExW(&wc)) return false;

    m_hwnd = CreateWindowExW(0, L"ClipboardManClass", L"Clipboard Man", 0, 0, 0, 0, 0, NULL, NULL, m_hInstance, NULL);
    if (!m_hwnd) return false;

    CreateTrayIcon();
    return true;
}

void Gui::Run() {
    MSG msg;
    while (GetMessageW(&msg, NULL, 0, 0)) {
        // 显式处理热键消息 (因为 RegisterHotKey(NULL, ...) 发送到线程队列)
        if (msg.message == WM_HOTKEY) {
            if (msg.wParam == 1) { // HOTKEY_ID = 1
                if (m_onPush) m_onPush();
            }
            continue;
        }

        if (!IsWindow(m_hSettingsWnd) || !IsDialogMessageW(m_hSettingsWnd, &msg)) {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
    }
}

HICON Gui::LoadPngAsIcon(const std::wstring& path) {
    Gdiplus::Bitmap bitmap(path.c_str());
    HICON hIcon = NULL;
    bitmap.GetHICON(&hIcon);
    return hIcon;
}

void Gui::CreateTrayIcon() {
    ZeroMemory(&m_nid, sizeof(m_nid));
    m_nid.cbSize = sizeof(m_nid);
    m_nid.hWnd = m_hwnd;
    m_nid.uID = 1;
    m_nid.uFlags = NIF_ICON | NIF_MESSAGE | NIF_TIP | NIF_INFO;
    m_nid.uCallbackMessage = WM_TRAYICON;
    
    // 尝试加载当前目录下的 app.png
    m_nid.hIcon = LoadPngAsIcon(L"app.png");
    
    // 如果没有 png，回退到默认图标
    if (!m_nid.hIcon) m_nid.hIcon = LoadIconW(NULL, (LPCWSTR)IDI_APPLICATION);
    
    wcscpy_s(m_nid.szTip, L"Clipboard Man");
    Shell_NotifyIconW(NIM_ADD, &m_nid);
}

void Gui::RemoveTrayIcon() {
    Shell_NotifyIconW(NIM_DELETE, &m_nid);
}

void Gui::ShowNotification(const std::wstring& title, const std::wstring& msg) {
    m_nid.uFlags |= NIF_INFO;
    wcscpy_s(m_nid.szInfoTitle, title.c_str());
    wcscpy_s(m_nid.szInfo, msg.c_str());
    m_nid.dwInfoFlags = NIIF_INFO;
    Shell_NotifyIconW(NIM_MODIFY, &m_nid);
}

void Gui::ShowContextMenu(POINT pt) {
    HMENU hMenu = CreatePopupMenu();
    AppendMenuW(hMenu, MF_STRING, IDM_PUSH, L"Push Clipboard"); // 移除硬编码提示
    AppendMenuW(hMenu, MF_STRING, IDM_SETTINGS, L"Settings");
    AppendMenuW(hMenu, MF_SEPARATOR, 0, NULL);
    AppendMenuW(hMenu, MF_STRING, IDM_EXIT, L"Exit");

    SetForegroundWindow(m_hwnd);
    int cmd = TrackPopupMenu(hMenu, TPM_RETURNCMD | TPM_NONOTIFY, pt.x, pt.y, 0, m_hwnd, NULL);
    
    if (cmd == IDM_PUSH) m_onPush();
    else if (cmd == IDM_SETTINGS) ShowSettingsWindow();
    else if (cmd == IDM_EXIT) PostQuitMessage(0);

    DestroyMenu(hMenu);
}

// --- Helpers ---

// RegisterHotKey uses: MOD_ALT (1), MOD_CONTROL (2), MOD_SHIFT (4), MOD_WIN (8)
// HOTKEY_CLASS uses:   HOTKEYF_SHIFT (1), HOTKEYF_CONTROL (2), HOTKEYF_ALT (4), HOTKEYF_EXT (8)
int Gui::HkModToWinMod(int hkMod) {
    int winMod = 0;
    if (hkMod & HOTKEYF_SHIFT)   winMod |= MOD_SHIFT;
    if (hkMod & HOTKEYF_CONTROL) winMod |= MOD_CONTROL;
    if (hkMod & HOTKEYF_ALT)     winMod |= MOD_ALT;
    return winMod;
}

int Gui::WinModToHkMod(int winMod) {
    int hkMod = 0;
    if (winMod & MOD_SHIFT)   hkMod |= HOTKEYF_SHIFT;
    if (winMod & MOD_CONTROL) hkMod |= HOTKEYF_CONTROL;
    if (winMod & MOD_ALT)     hkMod |= HOTKEYF_ALT;
    return hkMod;
}

void Gui::SetAutoStart(bool enable) {
    HKEY hKey;
    long res = RegOpenKeyExW(HKEY_CURRENT_USER, L"Software\\Microsoft\\Windows\\CurrentVersion\\Run", 0, KEY_WRITE, &hKey);
    if (res != ERROR_SUCCESS) return;

    if (enable) {
        wchar_t path[MAX_PATH];
        GetModuleFileNameW(NULL, path, MAX_PATH);
        RegSetValueExW(hKey, L"ClipboardManPush", 0, REG_SZ, (BYTE*)path, (DWORD)(wcslen(path) + 1) * 2);
    } else {
        RegDeleteValueW(hKey, L"ClipboardManPush");
    }
    RegCloseKey(hKey);
}

// --- Settings Window Logic ---

void Gui::ShowSettingsWindow() {
    if (m_hSettingsWnd) {
        SetForegroundWindow(m_hSettingsWnd);
        return;
    }

    WNDCLASSEXW wc = {0};
    wc.cbSize = sizeof(WNDCLASSEXW);
    wc.lpfnWndProc = SettingsWndProc;
    wc.hInstance = m_hInstance;
    wc.lpszClassName = L"ClipboardManSettings";
    wc.hbrBackground = (HBRUSH)(COLOR_BTNFACE + 1);
    RegisterClassExW(&wc);

    int width = 450, height = 400; // 增加高度以容纳新控件
    int x = (GetSystemMetrics(SM_CXSCREEN) - width) / 2;
    int y = (GetSystemMetrics(SM_CYSCREEN) - height) / 2;

    m_hSettingsWnd = CreateWindowExW(0, L"ClipboardManSettings", L"Settings", WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_VISIBLE,
        x, y, width, height, NULL, NULL, m_hInstance, NULL);

    HFONT hFont = CreateFontW(18, 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE, DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, DEFAULT_QUALITY, DEFAULT_PITCH | FF_SWISS, L"Segoe UI");

    auto CreateCtrl = [&](const wchar_t* cls, const wchar_t* txt, int x, int y, int w, int h, DWORD style, UINT_PTR id) {
        HWND hCtrl = CreateWindowExW(0, cls, txt, WS_CHILD | WS_VISIBLE | style, x, y, w, h, m_hSettingsWnd, (HMENU)id, m_hInstance, NULL);
        SendMessageW(hCtrl, WM_SETFONT, (WPARAM)hFont, TRUE);
        return hCtrl;
    };

    int cy = 25;
    // URL
    CreateCtrl(L"STATIC", L"Server URL:", 20, cy+3, 100, 20, 0, 0);
    std::wstring wUrl = Clipboard::Utf8ToWide(m_config.server_url);
    m_editUrl = CreateCtrl(L"EDIT", wUrl.c_str(), 130, cy, 280, 24, WS_BORDER | ES_AUTOHSCROLL, 0);
    cy += 40;

    // Path
    CreateCtrl(L"STATIC", L"Download Path:", 20, cy+3, 100, 20, 0, 0);
    std::wstring wPath = Clipboard::Utf8ToWide(m_config.download_path);
    m_editPath = CreateCtrl(L"EDIT", wPath.c_str(), 130, cy, 280, 24, WS_BORDER | ES_AUTOHSCROLL, 0);
    cy += 40;

    // Hotkey
    CreateCtrl(L"STATIC", L"Push Hotkey:", 20, cy+3, 100, 20, 0, 0);
    m_hkPush = CreateCtrl(HOTKEY_CLASS, L"", 130, cy, 280, 24, 0, 0);
    // Set current hotkey
    int hkMod = WinModToHkMod(m_config.hotkey_mod);
    SendMessage(m_hkPush, HKM_SETHOTKEY, MAKEWORD(m_config.hotkey_key, hkMod), 0);
    cy += 40;

    // Checkboxes
    m_checkImg = CreateCtrl(L"BUTTON", L"Auto Copy Received Images", 130, cy, 250, 25, BS_AUTOCHECKBOX, 0);
    SendMessageW(m_checkImg, BM_SETCHECK, m_config.auto_copy_image ? BST_CHECKED : BST_UNCHECKED, 0);
    cy += 30;

    m_checkFile = CreateCtrl(L"BUTTON", L"Auto Copy Received Files", 130, cy, 250, 25, BS_AUTOCHECKBOX, 0);
    SendMessageW(m_checkFile, BM_SETCHECK, m_config.auto_copy_file ? BST_CHECKED : BST_UNCHECKED, 0);
    cy += 30;

    m_checkAutoStart = CreateCtrl(L"BUTTON", L"Start on Boot (Run at login)", 130, cy, 250, 25, BS_AUTOCHECKBOX, 0);
    SendMessageW(m_checkAutoStart, BM_SETCHECK, m_config.auto_start ? BST_CHECKED : BST_UNCHECKED, 0);
    cy += 30;

    CreateCtrl(L"STATIC", L"Status:", 20, cy+3, 100, 20, 0, 0);
    m_statusLabel = CreateCtrl(L"STATIC", m_isConnected ? L"Connected" : L"Disconnected", 130, cy+3, 200, 20, 0, 0);
    cy += 50;

    // Buttons
    CreateCtrl(L"BUTTON", L"Save", 130, cy, 100, 35, WS_TABSTOP | BS_DEFPUSHBUTTON, ID_BTN_SAVE);
    CreateCtrl(L"BUTTON", L"Cancel", 240, cy, 100, 35, WS_TABSTOP, ID_BTN_CANCEL);
}

void Gui::UpdateStatus(bool connected) {
    m_isConnected = connected;
    // 使用 PostMessage 异步发送状态更新消息，避免跨线程死锁
    PostMessageW(m_hwnd, WM_UPDATE_STATUS, connected ? 1 : 0, 0);
}

void Gui::SaveSettingsFromWindow(HWND hDlg) {
    wchar_t buf[1024];
    
    GetWindowTextW(m_editUrl, buf, 1024);
    m_config.server_url = Clipboard::WideToUtf8(buf);

    GetWindowTextW(m_editPath, buf, 1024);
    m_config.download_path = Clipboard::WideToUtf8(buf);

    m_config.auto_copy_image = (SendMessageW(m_checkImg, BM_GETCHECK, 0, 0) == BST_CHECKED);
    m_config.auto_copy_file = (SendMessageW(m_checkFile, BM_GETCHECK, 0, 0) == BST_CHECKED);
    m_config.auto_start = (SendMessageW(m_checkAutoStart, BM_GETCHECK, 0, 0) == BST_CHECKED);

    // Read Hotkey from Control
    LRESULT hkRaw = SendMessage(m_hkPush, HKM_GETHOTKEY, 0, 0);
    int hkKey = LOBYTE(hkRaw);
    int hkMod = HIBYTE(hkRaw); // This uses HOTKEYF_* flags
    
    // Convert to RegisterHotKey flags (MOD_*)
    int winMod = HkModToWinMod(hkMod);
    
    spdlog::info("Settings Save: RawHK=0x{:X}, Key={}, HkMod={}, WinMod={}", (int)hkRaw, hkKey, hkMod, winMod);

    if (hkKey != 0) {
        m_config.hotkey_key = hkKey;
        m_config.hotkey_mod = winMod;
    } else {
        // If user cleared the box, maybe disable hotkey or keep old? 
        // Let's assume clear means disable/none
        m_config.hotkey_key = 0;
        m_config.hotkey_mod = 0;
    }

    // Apply Auto Start
    SetAutoStart(m_config.auto_start);

    // Save to JSON
    std::ofstream f("config.json");
    nlohmann::json j;
    j["server_url"] = m_config.server_url;
    j["download_path"] = m_config.download_path;
    j["auto_copy_image"] = m_config.auto_copy_image;
    j["auto_copy_file"] = m_config.auto_copy_file;
    j["hotkey_mod"] = m_config.hotkey_mod;
    j["hotkey_key"] = m_config.hotkey_key;
    j["auto_start"] = m_config.auto_start;
    f << j.dump(4);
    f.close();

    if (m_onReloadConfig) m_onReloadConfig();

    DestroyWindow(hDlg);
    m_hSettingsWnd = nullptr;
    ShowNotification(L"Success", L"Settings saved and applied.");
}

LRESULT CALLBACK Gui::SettingsWndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    if (msg == WM_COMMAND) {
        int id = LOWORD(wParam);
        if (id == ID_BTN_SAVE) {
            g_Gui->SaveSettingsFromWindow(hwnd);
        } else if (id == ID_BTN_CANCEL) {
            DestroyWindow(hwnd);
            g_Gui->m_hSettingsWnd = nullptr;
        }
    } else if (msg == WM_CLOSE) {
        DestroyWindow(hwnd);
        g_Gui->m_hSettingsWnd = nullptr;
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam);
}

LRESULT CALLBACK Gui::WndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    if (msg == WM_UPDATE_STATUS) {
        bool connected = (wParam != 0);
        if (g_Gui->m_statusLabel && IsWindow(g_Gui->m_statusLabel)) {
            SetWindowTextW(g_Gui->m_statusLabel, connected ? L"Connected" : L"Disconnected");
        }
        
        // 更新托盘提示
        std::wstring tip = L"Clipboard Man - ";
        tip += connected ? L"Connected" : L"Disconnected";
        wcscpy_s(g_Gui->m_nid.szTip, tip.c_str());
        g_Gui->m_nid.uFlags = NIF_TIP;
        Shell_NotifyIconW(NIM_MODIFY, &g_Gui->m_nid);
        return 0;
    }

    if (msg == WM_TRAYICON) {
        if (lParam == WM_RBUTTONUP) {
            POINT pt;
            GetCursorPos(&pt);
            g_Gui->ShowContextMenu(pt);
        } else if (lParam == WM_LBUTTONDBLCLK) {
            g_Gui->ShowSettingsWindow();
        }
    } else if (msg == WM_DESTROY) {
        PostQuitMessage(0);
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam);
}
