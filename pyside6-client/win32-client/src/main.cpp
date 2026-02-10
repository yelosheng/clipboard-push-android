#include <windows.h>
#include "core/Logger.h"
#include "core/Config.h"
#include "core/Utils.h"
#include "core/SyncLogic.h"
#include "platform/Platform.h"
#include "platform/Clipboard.h"
#include "platform/Hotkey.h"
#include "core/SocketIOService.h"
#include "platform/ClipboardMonitor.h"
#include "ui/TrayIcon.h"
#include "ui/MainWindow.h"
#include "ui/SettingsWindow.h"
#include "ui/Resource.h"

#include "core/Crypto.h"
#include "core/Network.h"
#include <chrono>
#include <iomanip>
#include <sstream>
#include <thread>

#define WM_TRAYICON (WM_USER + 1)

// Global state for sync logic
static bool g_isProcessingRemoteSync = false;

namespace ClipboardPush {

std::string GetCurrentTimestamp() {
    auto now = std::chrono::system_clock::now();
    auto in_time_t = std::chrono::system_clock::to_time_t(now);
    std::tm tm_struct;
    localtime_s(&tm_struct, &in_time_t);
    std::stringstream ss;
    ss << std::put_time(&tm_struct, "%H:%M:%S");
    return ss.str();
}

void PushText(const std::string& text) {
    auto& config = ClipboardPush::Config::Instance().Data();
    if (config.room_key.empty()) return;

    auto key = ClipboardPush::Crypto::DecodeKey(config.room_key);
    std::vector<uint8_t> plain(text.begin(), text.end());
    auto enc = ClipboardPush::Crypto::Encrypt(key, plain);
    
    if (enc) {
        std::string b64 = ClipboardPush::Crypto::ToBase64(*enc);
        std::string url = config.relay_server_url + "/api/relay";
        
        nlohmann::json j;
        j["room"] = config.room_id;
        j["client_id"] = config.device_id;
        j["content"] = b64;
        j["encrypted"] = true;
        j["timestamp"] = GetCurrentTimestamp();

        auto res = ClipboardPush::Network::HttpClient::Post(url, j.dump());
        if (res.status == 200) {
            LOG_INFO("Push success");
        } else {
            LOG_ERROR("Push failed: %d", res.status);
        }
    }
}

} // namespace ClipboardPush

LRESULT CALLBACK WndProc(HWND hWnd, UINT message, WPARAM wParam, LPARAM lParam) {
    // Handle Clipboard Monitoring
    ClipboardPush::Platform::ClipboardMonitor::Instance().HandleMessage(message, wParam, lParam);

    switch (message) {
    case WM_TRAYICON:
        if (LOWORD(lParam) == WM_RBUTTONUP) {
            ClipboardPush::UI::TrayIcon::Instance().ShowContextMenu(hWnd);
        } else if (LOWORD(lParam) == WM_LBUTTONDBLCLK) {
            ClipboardPush::UI::MainWindow::Instance().Show();
        }
        break;
    case WM_COMMAND:
        switch (LOWORD(wParam)) {
        case IDM_TRAY_EXIT:
            PostQuitMessage(0);
            break;
        case IDM_TRAY_OPEN:
            ClipboardPush::UI::MainWindow::Instance().Show();
            break;
        case IDM_TRAY_SETTINGS:
            ClipboardPush::UI::SettingsWindow::Instance().Show();
            break;
        case IDC_SETTINGS_RECONNECT:
            {
                auto& d = ClipboardPush::Config::Instance().Data();
                ClipboardPush::SocketIOService::Instance().Connect(d.relay_server_url, d.room_id, d.device_id);
            }
            break;
        case IDC_SETTINGS_SAVE:
            {
                LOG_INFO("Settings saved signal received, updating components...");
                auto& d = ClipboardPush::Config::Instance().Data();
                ClipboardPush::Platform::Hotkey::Instance().Register(hWnd, d.push_hotkey);
                ClipboardPush::SocketIOService::Instance().Connect(d.relay_server_url, d.room_id, d.device_id);
            }
            break;
        }
        break;
    case WM_HOTKEY:
        ClipboardPush::Platform::Hotkey::Instance().HandleMessage(wParam);
        break;
    case WM_DESTROY:
        PostQuitMessage(0);
        break;
    default:
        return DefWindowProc(hWnd, message, wParam, lParam);
    }
    return 0;
}

int main() {
    HINSTANCE hInstance = GetModuleHandle(NULL);
    ClipboardPush::Platform::Init();
    ClipboardPush::Config::Instance().Load();
    auto& data = ClipboardPush::Config::Instance().Data();
    
    // Create a dummy window to handle messages
    const wchar_t CLASS_NAME[] = L"ClipboardPushMessageWindow";
    WNDCLASSW wc = {};
    wc.lpfnWndProc = WndProc;
    wc.hInstance = hInstance;
    wc.lpszClassName = CLASS_NAME;
    RegisterClassW(&wc);

    HWND hWnd = CreateWindowExW(0, CLASS_NAME, L"Clipboard Push v3.0", 0, 0, 0, 0, 0, HWND_MESSAGE, NULL, hInstance, NULL);
    if (!hWnd) return 0;

    // Init UI
    ClipboardPush::UI::MainWindow::Instance().Create(hInstance);
    ClipboardPush::UI::SettingsWindow::Instance().Create(hInstance);
    ClipboardPush::UI::TrayIcon::Instance().Init(hWnd, hInstance);

    // Setup Socket.IO
    auto& sio = ClipboardPush::SocketIOService::Instance();
    sio.SetCallbacks(
        [](const std::string& content, bool encrypted) {
            if (content.empty()) return;
            
            g_isProcessingRemoteSync = true;
            std::string finalText = content;

            if (encrypted) {
                auto& config = ClipboardPush::Config::Instance().Data();
                auto key = ClipboardPush::Crypto::DecodeKey(config.room_key);
                auto encData = ClipboardPush::Crypto::FromBase64(content);
                auto dec = ClipboardPush::Crypto::Decrypt(key, encData);
                if (dec) {
                    finalText = std::string(dec->begin(), dec->end());
                } else {
                    LOG_ERROR("Failed to decrypt remote content");
                    g_isProcessingRemoteSync = false;
                    return;
                }
            }

            ClipboardPush::Platform::Clipboard::SetText(finalText);
            
            std::wstring wMsg = L"Synced: " + ClipboardPush::Utils::ToWide(finalText.substr(0, 30));
            if (finalText.length() > 30) wMsg += L"...";
            ClipboardPush::UI::TrayIcon::Instance().ShowMessage(L"Clipboard Received", wMsg);
            
            // Brief delay to ensure WM_CLIPBOARDUPDATE is ignored
            std::thread([]() {
                std::this_thread::sleep_for(std::chrono::milliseconds(500));
                g_isProcessingRemoteSync = false;
            }).detach();
        },
        [](const nlohmann::json& data) {
            LOG_INFO("Received file sync (logic to be added)");
        },
        [](bool connected) {
            std::wstring status = connected ? L"Connected" : L"Disconnected";
            ClipboardPush::UI::MainWindow::Instance().SetStatus(status);
        }
    );
    sio.Connect(data.relay_server_url, data.room_id, data.device_id);

    // Setup Clipboard Monitor
    ClipboardPush::Platform::ClipboardMonitor::Instance().SetCallback([]() {
        if (g_isProcessingRemoteSync) return;
        
        LOG_INFO("Local clipboard changed, pushing...");
        auto cb = ClipboardPush::Platform::Clipboard::Get();
        if (cb.type == ClipboardPush::Platform::ClipboardType::Text && !cb.text.empty()) {
            ClipboardPush::PushText(cb.text);
        }
    });
    ClipboardPush::Platform::ClipboardMonitor::Instance().Start(hWnd);

    // Register Hotkey
    ClipboardPush::Platform::Hotkey::Instance().SetCallback([]() {
        LOG_INFO("Hotkey Triggered!");
        auto cb = ClipboardPush::Platform::Clipboard::Get();
        if (cb.type == ClipboardPush::Platform::ClipboardType::Text && !cb.text.empty()) {
            ClipboardPush::PushText(cb.text);
            ClipboardPush::UI::TrayIcon::Instance().ShowMessage(L"Clipboard Pushed", L"Content sent via hotkey");
        }
    });
    ClipboardPush::Platform::Hotkey::Instance().Register(hWnd, data.push_hotkey);

    // Show UI
    if (!data.start_minimized) {
        ClipboardPush::UI::MainWindow::Instance().Show();
    } else {
        ClipboardPush::UI::TrayIcon::Instance().ShowMessage(L"Clipboard Push v3.0", L"I am now running ultra-light in your system tray!");
    }

    // Message Loop
    MSG msg = {};
    while (GetMessage(&msg, NULL, 0, 0)) {
        TranslateMessage(&msg);
        DispatchMessage(&msg);
    }

    ClipboardPush::Platform::ClipboardMonitor::Instance().Stop(hWnd);
    ClipboardPush::UI::TrayIcon::Instance().Remove();
    ClipboardPush::Platform::Shutdown();
    return 0;
}