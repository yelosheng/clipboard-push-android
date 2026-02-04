#pragma once
#include <windows.h>
#include <shellapi.h>
#include <string>
#include <functional>
#include "Config.hpp"

#define WM_TRAYICON (WM_USER + 1)
#define WM_UPDATE_STATUS (WM_USER + 2)

class Gui {
public:
    Gui(HINSTANCE hInstance, AppConfig& config, std::function<void()> onPush, std::function<void()> onReloadConfig);
    ~Gui();

    bool Init();
    void Run();
    void ShowNotification(const std::wstring& title, const std::wstring& msg);
    void UpdateStatus(bool connected);

private:
    static LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam);
    static LRESULT CALLBACK SettingsWndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam);
    
    void CreateTrayIcon();
    void RemoveTrayIcon();
    void ShowContextMenu(POINT pt);
    void ShowSettingsWindow();
    void SaveSettingsFromWindow(HWND hDlg);
    
    HICON LoadPngAsIcon(const std::wstring& path);

    // Auto-start helper
    void SetAutoStart(bool enable);
    // Hotkey helper: HKM <-> MOD
    int HkModToWinMod(int hkMod);
    int WinModToHkMod(int winMod);

    HINSTANCE m_hInstance;
    HWND m_hwnd; // 主窗口（隐藏）
    HWND m_hSettingsWnd = nullptr; // 设置窗口
    NOTIFYICONDATA m_nid;
    AppConfig& m_config;
    
    std::function<void()> m_onPush;
    std::function<void()> m_onReloadConfig;

    bool m_isConnected = false;

    // Controls for settings
    HWND m_editUrl, m_editPath, m_checkImg, m_checkFile;
    HWND m_hkPush, m_checkAutoStart;
    HWND m_statusLabel;
};