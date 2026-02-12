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

#include <filesystem>
#include <fstream>

#define WM_TRAYICON (WM_USER + 1)

using namespace ClipboardPush;
namespace fs = std::filesystem;

// Global state for sync logic
static bool g_isProcessingRemoteSync = false;

// Forward declarations
void PushText(const std::string& text);

void OnRemoteFileReceived(const nlohmann::json& data) {
    try {
        std::string url = data.value("download_url", "");
        std::string filename = data.value("filename", "received_file");
        std::string type = data.value("type", "file");

        if (url.empty()) return;

        LOG_INFO("Downloading file: %s", filename.c_str());
        auto encData = Network::HttpClient::Get(url);
        if (!encData) {
            LOG_ERROR("Failed to download file");
            return;
        }

        auto& config = Config::Instance().Data();
        auto key = Crypto::DecodeKey(config.room_key);
        auto decData = Crypto::Decrypt(key, *encData);
        if (!decData) {
            LOG_ERROR("Failed to decrypt file");
            return;
        }

        // Ensure download path exists
        fs::path downloadDir(Utils::ToWide(config.download_path));
        if (!fs::exists(downloadDir)) {
            fs::create_directories(downloadDir);
        }

        // Handle duplicate filenames
        fs::path filePath = downloadDir / Utils::ToWide(filename);
        int count = 1;
        std::wstring stem = filePath.stem().wstring();
        std::wstring ext = filePath.extension().wstring();
        while (fs::exists(filePath)) {
            filePath = downloadDir / (stem + L"_" + std::to_wstring(count++) + ext);
        }

        // Save file
        std::ofstream outfile(filePath, std::ios::binary);
        outfile.write((char*)decData->data(), decData->size());
        outfile.close();

        LOG_INFO("File saved to %s", filePath.string().c_str());
        UI::TrayIcon::Instance().ShowMessage(L"File Received", Utils::ToWide(filename));

        // Auto copy to clipboard
        g_isProcessingRemoteSync = true;
        if (type == "image" && config.auto_copy_image) {
            Platform::Clipboard::SetImageFromFile(filePath.string());
        } else if (config.auto_copy_file) {
            Platform::Clipboard::SetFiles({filePath.string()});
        }
        
        // Reset sync flag after a short delay
        std::thread([]() {
            std::this_thread::sleep_for(std::chrono::milliseconds(500));
            g_isProcessingRemoteSync = false;
        }).detach();

    } catch (const std::exception& e) {
        LOG_ERROR("Error in file sync: %s", e.what());
    }
}

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
        j["event"] = "clipboard_sync";
        j["sender_id"] = config.device_id;
        
        nlohmann::json data;
        data["room"] = config.room_id;
        data["content"] = b64;
        data["encrypted"] = true;
        data["timestamp"] = GetCurrentTimestamp();
        data["source"] = config.device_id;
        
        j["data"] = data;

        auto res = ClipboardPush::Network::HttpClient::Post(url, j.dump());
        if (res.status == 200) {
            LOG_INFO("Push success");
        } else {
            LOG_ERROR("Push failed: %d, Response: %s", res.status, res.body.c_str());
        }
    }
}

void PushFileData(const std::vector<uint8_t>& data, const std::string& filename, const std::string& fileType) {
    auto& config = ClipboardPush::Config::Instance().Data();
    if (config.room_key.empty()) return;

    auto key = ClipboardPush::Crypto::DecodeKey(config.room_key);
    auto enc = ClipboardPush::Crypto::Encrypt(key, data);
    if (!enc) return;

    // 1. Request upload auth
    std::string authUrl = config.relay_server_url + "/api/file/upload_auth";
    nlohmann::json authPayload;
    authPayload["filename"] = filename;
    authPayload["size"] = enc->size();
    authPayload["content_type"] = "application/octet-stream";
    
    auto authRes = ClipboardPush::Network::HttpClient::Post(authUrl, authPayload.dump());
    if (authRes.status != 200) {
        LOG_ERROR("Upload auth failed: %d", authRes.status);
        return;
    }

    try {
        auto authJ = nlohmann::json::parse(authRes.body);
        std::string uploadUrl = authJ.value("upload_url", "");
        std::string downloadUrl = authJ.value("download_url", "");

        if (uploadUrl.empty()) return;

        // 2. Upload file
        LOG_INFO("Uploading file...");
        auto putRes = ClipboardPush::Network::HttpClient::Put(uploadUrl, *enc);
        if (putRes.status != 200) {
            LOG_ERROR("File upload failed: %d", putRes.status);
            return;
        }

        // 3. Relay notification
        std::string relayUrl = config.relay_server_url + "/api/relay";
        nlohmann::json relayPayload;
        relayPayload["room"] = config.room_id;
        relayPayload["event"] = "file_sync";
        relayPayload["sender_id"] = config.device_id;
        
        nlohmann::json d;
        d["room"] = config.room_id;
        d["download_url"] = downloadUrl;
        d["filename"] = filename;
        d["type"] = fileType;
        d["timestamp"] = GetCurrentTimestamp();
        relayPayload["data"] = d;

        ClipboardPush::Network::HttpClient::Post(relayUrl, relayPayload.dump());
        LOG_INFO("File sync pushed successfully");
    } catch (...) {
        LOG_ERROR("Failed to process upload response");
    }
}

void PushImage(const std::vector<uint8_t>& pngData) {
    std::string filename = "img_" + std::to_string(std::chrono::system_clock::to_time_t(std::chrono::system_clock::now())) + ".png";
    PushFileData(pngData, filename, "image");
}

void PushPhysicalFile(const std::string& filePath) {
    std::wstring wPath = Utils::ToWide(filePath);
    std::ifstream file(wPath, std::ios::binary);
    if (!file.is_open()) {
        LOG_ERROR("Failed to open file for pushing: %s", filePath.c_str());
        return;
    }

    std::vector<uint8_t> data((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
    file.close();

    fs::path p(wPath);
    std::string utf8Filename = Utils::ToUtf8(p.filename().wstring());
    PushFileData(data, utf8Filename, "file");
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
        case IDM_TRAY_PUSH:
            {
                LOG_INFO("Manual Tray Push Triggered");
                auto cb = ClipboardPush::Platform::Clipboard::Get();
                if (cb.type == ClipboardPush::Platform::ClipboardType::Text && !cb.text.empty()) {
                    ClipboardPush::PushText(cb.text);
                    ClipboardPush::UI::TrayIcon::Instance().ShowMessage(L"Clipboard Pushed", L"Text content sent successfully");
                } else if (cb.type == ClipboardPush::Platform::ClipboardType::Image) {
                    ClipboardPush::PushImage(cb.image_data);
                    ClipboardPush::UI::TrayIcon::Instance().ShowMessage(L"Clipboard Pushed", L"Image content sent successfully");
                } else if (cb.type == ClipboardPush::Platform::ClipboardType::Files) {
                    for (const auto& file : cb.files) {
                        ClipboardPush::PushPhysicalFile(file);
                    }
                    ClipboardPush::UI::TrayIcon::Instance().ShowMessage(L"Clipboard Pushed", L"File(s) sent successfully");
                }
            }
            break;
        case IDC_SETTINGS_RECONNECT:
            {
                auto& d = ClipboardPush::Config::Instance().Data();
                ClipboardPush::SocketIOService::Instance().Disconnect();
                ClipboardPush::SocketIOService::Instance().Connect(d.relay_server_url, d.room_id, d.device_id);
            }
            break;
        case IDC_SETTINGS_SAVE:
            {
                LOG_INFO("Settings saved signal received, updating components...");
                auto& d = ClipboardPush::Config::Instance().Data();
                ClipboardPush::Utils::SetAutoStart(d.auto_start);
                ClipboardPush::Platform::Hotkey::Instance().Register(hWnd, d.push_hotkey);
                ClipboardPush::SocketIOService::Instance().Disconnect();
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

int WINAPI wWinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, PWSTR pCmdLine, int nCmdShow) {
    // Single Instance Protection
    HANDLE hMutex = CreateMutexW(NULL, TRUE, L"Global\\ClipboardPushWin32_SingleInstance_Mutex");
    if (hMutex == NULL || GetLastError() == ERROR_ALREADY_EXISTS) {
        if (hMutex) CloseHandle(hMutex);
        return 0;
    }

    SetProcessDPIAware();
    ClipboardPush::Platform::Init();
    ClipboardPush::Config::Instance().Load();
    auto& data = ClipboardPush::Config::Instance().Data();
    
    // Sync auto-start state
    ClipboardPush::Utils::SetAutoStart(data.auto_start);
    
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
    ClipboardPush::UI::SettingsWindow::Instance().Create(hInstance, hWnd);
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
            // Run in a separate thread to not block the socket service
            std::thread([data]() {
                OnRemoteFileReceived(data);
            }).detach();
        },
        [](ConnectionStatus status) {
            std::wstring statusStr;
            COLORREF color = RGB(128, 128, 128); // Gray default
            
            switch(status) {
                case ConnectionStatus::ConnectedLonely: 
                    statusStr = L"Connected (Lonely)"; 
                    color = RGB(255, 215, 0); // Gold/Yellow
                    break;
                case ConnectionStatus::ConnectedSynced: 
                    statusStr = L"Connected (Synced)"; 
                    color = RGB(50, 205, 50); // LimeGreen
                    break;
                case ConnectionStatus::Disconnected: 
                    statusStr = L"Disconnected"; 
                    color = RGB(128, 128, 128); // Gray
                    break;
                case ConnectionStatus::Connecting: 
                    statusStr = L"Connecting..."; 
                    color = RGB(100, 100, 255); // Light Blue
                    break;
                case ConnectionStatus::Retrying: 
                    statusStr = L"Retrying..."; 
                    color = RGB(255, 69, 0); // OrangeRed
                    break;
            }
            ClipboardPush::UI::MainWindow::Instance().SetStatus(statusStr);
            
            // Update Tray Icon with dynamic indicator
            HICON hNew = ClipboardPush::Platform::CreateStatusIcon(GetModuleHandle(NULL), IDI_APP_ICON, color);
            if (hNew) {
                ClipboardPush::UI::TrayIcon::Instance().UpdateIcon(hNew);
            }
        },
        [](int secondsLeft) {
            std::wstring statusStr = L"Retrying in " + std::to_wstring(secondsLeft) + L"s...";
            ClipboardPush::UI::MainWindow::Instance().SetStatus(statusStr);
        }
    );
    sio.Connect(data.relay_server_url, data.room_id, data.device_id);

    // Setup Clipboard Monitor
    ClipboardPush::Platform::ClipboardMonitor::Instance().SetCallback([]() {
        if (g_isProcessingRemoteSync) return;
        LOG_DEBUG("Local clipboard changed (automatic sync is disabled)");
    });
    ClipboardPush::Platform::ClipboardMonitor::Instance().Start(hWnd);

    // Register Hotkey
    ClipboardPush::Platform::Hotkey::Instance().SetCallback([]() {
        LOG_INFO("Hotkey Triggered!");
        auto cb = ClipboardPush::Platform::Clipboard::Get();
        if (cb.type == ClipboardPush::Platform::ClipboardType::Text && !cb.text.empty()) {
            ClipboardPush::PushText(cb.text);
            ClipboardPush::UI::TrayIcon::Instance().ShowMessage(L"Clipboard Pushed", L"Text content sent via hotkey");
        } else if (cb.type == ClipboardPush::Platform::ClipboardType::Image) {
            ClipboardPush::PushImage(cb.image_data);
            ClipboardPush::UI::TrayIcon::Instance().ShowMessage(L"Clipboard Pushed", L"Image content sent via hotkey");
        } else if (cb.type == ClipboardPush::Platform::ClipboardType::Files) {
            for (const auto& file : cb.files) {
                ClipboardPush::PushPhysicalFile(file);
            }
            ClipboardPush::UI::TrayIcon::Instance().ShowMessage(L"Clipboard Pushed", L"File(s) sent via hotkey");
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