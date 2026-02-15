#include <winsock2.h>
#include <windows.h>
#include "core/Logger.h"
#include "core/Config.h"
#include "core/Utils.h"
#include "core/SyncLogic.h"
#include "platform/Platform.h"
#include "platform/Clipboard.h"
#include "platform/Hotkey.h"
#include "core/SocketIOService.h"
#include "core/LocalServer.h"
#include "platform/ClipboardMonitor.h"
#include "ui/TrayIcon.h"
#include "ui/MainWindow.h"
#include "ui/SettingsWindow.h"
#include "ui/NotificationWindow.h"
#include "ui/Resource.h"

#include "core/Crypto.h"
#include "core/Network.h"
#include <chrono>
#include <iomanip>
#include <sstream>
#include <thread>
#include <mutex>
#include <map>

#include <filesystem>
#include <fstream>

#define WM_TRAYICON (WM_USER + 1)

using namespace ClipboardPush;
namespace fs = std::filesystem;

namespace ClipboardPush {

// Global state for sync logic
static bool g_isProcessingRemoteSync = false;

struct PendingPush {
    std::string room;
    std::string transfer_id;
    std::string file_id;
    std::vector<uint8_t> data;
    std::string filename;
    std::string type;
    std::atomic<bool> completed{ false };
    std::atomic<bool> upload_requested{ false };
    std::atomic<bool> upload_started{ false };
    std::atomic<bool> need_relay{ false };
};

static std::mutex g_pendingMutex;
static std::map<std::string, std::shared_ptr<PendingPush>> g_pendingPushes;

void ProcessReceivedFile(const std::string& filePath, const std::string& filename, const std::string& type) {
    auto& config = Config::Instance().Data();
    
    LOG_INFO("Processing received file: %s", filename.c_str());
    ShowNotification(L"File Received", Utils::ToWide(filename));

    // Auto copy to clipboard
    g_isProcessingRemoteSync = true;
    if (type == "image" && config.auto_copy_image) {
        Platform::Clipboard::SetImageFromFile(filePath);
    } else if (config.auto_copy_file) {
        Platform::Clipboard::SetFiles({filePath});
    }
    
    // Reset sync flag after a short delay
    std::thread([]() {
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
        g_isProcessingRemoteSync = false;
    }).detach();
}

void HandleIncomingAnnouncement(const nlohmann::json& data) {
    auto& config = Config::Instance().Data();
    std::string transfer_id = data.value("transfer_id", "");
    std::string file_id = data.value("file_id", "");
    std::string filename = data.value("filename", "received_file");
    std::string local_url = data.value("local_url", "");
    std::string sender_id = data.value("sender_client_id", "unknown");
    std::string type = data.value("type", "file");

    if (local_url.empty() || transfer_id.empty()) return;

    LOG_INFO("Receiver Mode: Peer announced file via LAN. ID: %s", transfer_id.c_str());

    std::thread([transfer_id, file_id, filename, local_url, type, config]() {
        // 1. Attempt LAN Pull
        LOG_INFO("Attempting LAN pull from %s", local_url.c_str());
        
        // Add Room-ID header for security
        std::map<std::string, std::string> headers;
        headers["X-Room-ID"] = config.room_id;
        
        auto res = Network::HttpClient::GetWithHeaders(local_url, headers);
        
        if (res && !res->empty()) {
            LOG_INFO("LAN Pull Successful. Decrypting...");
            
            // 2. Decrypt data
            auto key = Crypto::DecodeKey(config.room_key);
            auto decData = Crypto::Decrypt(key, *res);
            if (!decData) {
                LOG_ERROR("Failed to decrypt data pulled via LAN");
                return;
            }

            // 3. Save file
            fs::path downloadDir(Utils::ToWide(config.download_path));
            if (!fs::exists(downloadDir)) fs::create_directories(downloadDir);
            
            fs::path filePath = downloadDir / Utils::ToWide(filename);
            // Handle duplicates
            int count = 1;
            std::wstring stem = filePath.stem().wstring();
            std::wstring ext = filePath.extension().wstring();
            while (fs::exists(filePath)) {
                filePath = downloadDir / (stem + L"_" + std::to_wstring(count++) + ext);
            }

            std::ofstream ofs(filePath, std::ios::binary);
            ofs.write((char*)decData->data(), decData->size());
            ofs.close();

            // 4. Process (UI & Clipboard)
            ProcessReceivedFile(filePath.string(), filename, type);

            // 5. Send Success Signal
            nlohmann::json ack;
            ack["protocol_version"] = "4.0";
            ack["room"] = config.room_id;
            ack["transfer_id"] = transfer_id;
            ack["file_id"] = file_id;
            ack["method"] = "lan";
            ack["received_at_ms"] = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
            
            SocketIOService::Instance().Emit("file_sync_completed", ack);
            LOG_INFO("Sent file_sync_completed for ID: %s", transfer_id.c_str());
        } else {
            LOG_WARNING("LAN Pull Failed. Requesting Cloud Relay...");
            
            // 5. Send Fallback Signal
            nlohmann::json req;
            req["protocol_version"] = "4.0";
            req["room"] = config.room_id;
            req["transfer_id"] = transfer_id;
            req["file_id"] = file_id;
            req["reason"] = "lan_unreachable";
            req["reported_at_ms"] = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
            
            SocketIOService::Instance().Emit("file_need_relay", req);
        }
    }).detach();
}

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
        
                ProcessReceivedFile(filePath.string(), filename, type);
            } catch (const std::exception& e) {
        LOG_ERROR("Error in file sync: %s", e.what());
    }
}

void PerformCloudUpload(const std::vector<uint8_t>& encData, const std::string& filename, const std::string& fileType);

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
    auto& config = Config::Instance().Data();
    if (config.room_key.empty()) return;

    auto key = Crypto::DecodeKey(config.room_key);
    std::vector<uint8_t> plain(text.begin(), text.end());
    auto enc = Crypto::Encrypt(key, plain);
    
    if (enc) {
        std::string b64 = Crypto::ToBase64(*enc);
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

        auto res = Network::HttpClient::Post(url, j.dump());
        if (res.status == 200) {
            LOG_INFO("Push success");
        } else {
            LOG_ERROR("Push failed: %d, Response: %s", res.status, res.body.c_str());
        }
    }
}

void PushFileData(const std::vector<uint8_t>& data, const std::string& filename, const std::string& fileType) {
    auto& config = Config::Instance().Data();
    if (config.room_key.empty()) return;

    // Encrypt data FIRST
    auto key = Crypto::DecodeKey(config.room_key);
    auto enc = Crypto::Encrypt(key, data);
    if (!enc) return;

    // 1. Create Unique IDs (Stable for the whole process)
    auto now = std::chrono::system_clock::now();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()).count();
    std::string file_id = "f_" + std::to_string(ms);
    std::string transfer_id = "tr_" + std::to_string(ms) + "_" + std::to_string(rand() % 100);

    // 2. Save a local copy to temp folder for LAN sync
    fs::path localPath;
    try {
        fs::path tempDir = fs::path(Utils::GetAppDir()) / L"temp";
        if (!fs::exists(tempDir)) fs::create_directories(tempDir);
        localPath = tempDir / Utils::ToWide(filename);
        std::ofstream ofs(localPath, std::ios::binary);
        ofs.write((char*)data.data(), data.size());
        ofs.close();
    } catch (...) {
        LOG_ERROR("Failed to save temp copy for LAN sync");
        return;
    }

    // 3. Register in Pending Queue
    auto pending = std::make_shared<PendingPush>();
    pending->room = config.room_id;
    pending->file_id = file_id;
    pending->transfer_id = transfer_id;
    pending->data = *enc;
    pending->filename = filename;
    pending->type = fileType;
    {
        std::lock_guard<std::mutex> lock(g_pendingMutex);
        g_pendingPushes[transfer_id] = pending;
    }

    // 4. Send Announcement (Protocol 4.0 schema)
    nlohmann::json announce;
    announce["protocol_version"] = "4.0";
    announce["room"] = config.room_id;
    announce["transfer_id"] = transfer_id;
    announce["file_id"] = file_id;
    announce["filename"] = filename;
    announce["type"] = fileType;
    announce["size_bytes"] = data.size();
    announce["sender_client_id"] = config.device_id;
    announce["local_url"] = "http://" + LocalServer::Instance().GetIP() + ":" + std::to_string(LocalServer::Instance().GetPort()) + "/files/" + filename;
    announce["sent_at_ms"] = ms;
    
    SocketIOService::Instance().Emit("file_available", announce);
    LOG_INFO("tx file_available: id=%s, room=%s", transfer_id.c_str(), config.room_id.c_str());

    // 5. Start Background Decision Thread
    int timeoutSecs = config.lan_timeout;
    std::thread([pending, localPath, transfer_id, timeoutSecs]() {
        // Wait for server command or app ack
        for (int i = 0; i < timeoutSecs * 20; ++i) {
            if (pending->completed || pending->upload_requested || pending->need_relay) break;
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
        }

        std::error_code ec;
        if (pending->completed) {
            LOG_INFO("LAN sync finished: id=%s", transfer_id.c_str());
            fs::remove(localPath, ec);
        } else {
            // Idempotent Upload trigger
            if (!pending->upload_started.exchange(true)) {
                LOG_INFO("upload start: id=%s (reason: %s)", transfer_id.c_str(), 
                    pending->upload_requested ? "server_directed" : (pending->need_relay ? "app_fallback" : "timeout"));
                PerformCloudUpload(pending->data, pending->filename, pending->type);
                LOG_INFO("upload end: id=%s", transfer_id.c_str());
            }
            std::this_thread::sleep_for(std::chrono::seconds(30));
            fs::remove(localPath, ec);
        }

        // Cleanup from pending queue
        {
            std::lock_guard<std::mutex> lock(g_pendingMutex);
            g_pendingPushes.erase(transfer_id);
        }
    }).detach();
}

void PerformCloudUpload(const std::vector<uint8_t>& encData, const std::string& filename, const std::string& fileType) {
    auto& config = Config::Instance().Data();
    
    // 1. Request upload auth
    std::string authUrl = config.relay_server_url + "/api/file/upload_auth";
    nlohmann::json authPayload;
    authPayload["filename"] = filename;
    authPayload["size"] = encData.size();
    authPayload["content_type"] = "application/octet-stream";
    
    auto authRes = Network::HttpClient::Post(authUrl, authPayload.dump());
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
        LOG_INFO("Uploading to cloud...");
        auto putRes = Network::HttpClient::Put(uploadUrl, encData);
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

        Network::HttpClient::Post(relayUrl, relayPayload.dump());
        LOG_INFO("Cloud sync pushed successfully");
    } catch (...) {
        LOG_ERROR("Failed to process cloud upload");
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

// Global Message Window handle
HWND g_hMsgWnd = NULL;

void ShowNotification(const std::wstring& title, const std::wstring& message, UI::NotificationStyle style) {
    auto& config = Config::Instance().Data();
    if (!config.show_notifications) return;

    auto* data = new UI::NotificationData{ title, message, style };
    if (!PostMessageW(g_hMsgWnd, WM_SHOW_NOTIFICATION, 0, reinterpret_cast<LPARAM>(data))) {
        delete data;
    }
}

} // namespace ClipboardPush

LRESULT CALLBACK WndProc(HWND hWnd, UINT message, WPARAM wParam, LPARAM lParam) {
    // Handle Clipboard Monitoring
    ClipboardPush::Platform::ClipboardMonitor::Instance().HandleMessage(message, wParam, lParam);

    switch (message) {
    case WM_SHOW_NOTIFICATION:
        ClipboardPush::UI::NotificationWindow::HandleMessage(lParam);
        break;
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

int APIENTRY wWinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, LPWSTR lpCmdLine, int nCmdShow) {
    // Single Instance Protection
    HANDLE hMutex = CreateMutexW(NULL, TRUE, L"Global\\ClipboardPushWin32_SingleInstance_Mutex");
    if (hMutex == NULL || GetLastError() == ERROR_ALREADY_EXISTS) {
        if (hMutex) CloseHandle(hMutex);
        return 0;
    }

    SetProcessDPIAware();
    // HINSTANCE hInstance = GetModuleHandle(NULL);
    ClipboardPush::Platform::Init();

    // Cleanup temp folder on startup
    try {
        fs::path tempDir = fs::path(Utils::GetAppDir()) / L"temp";
        if (fs::exists(tempDir)) {
            for (const auto& entry : fs::directory_iterator(tempDir)) {
                std::error_code ec;
                fs::remove(entry.path(), ec);
            }
        }
    } catch (...) {}

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
    g_hMsgWnd = hWnd;

    // Register UI classes
    ClipboardPush::UI::NotificationWindow::RegisterClass(hInstance);

    // Init UI
    ClipboardPush::UI::MainWindow::Instance().Create(hInstance);
    ClipboardPush::UI::SettingsWindow::Instance().Create(hInstance, hWnd);
    ClipboardPush::UI::TrayIcon::Instance().Init(hWnd, hInstance);

    // Start Local Server for LAN Sync
    ClipboardPush::LocalServer::Instance().Start();

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
            
            std::wstring wMsg = ClipboardPush::Utils::ToWide(finalText.substr(0, 30));
            if (finalText.length() > 30) wMsg += L"...";
            ClipboardPush::ShowNotification(L"Clipboard Received", wMsg);
            
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
    sio.SetSignalingCallback([](const std::string& event, const nlohmann::json& data) {
        auto& config = Config::Instance().Data();
        std::string room = data.value("room", "");
        
        if (event == "peer_evicted") {
            LOG_WARNING("Peer evicted from room. Re-joining...");
            SocketIOService::Instance().Disconnect();
            SocketIOService::Instance().Connect(config.relay_server_url, config.room_id, config.device_id);
            return;
        }

        if (event == "file_available") {
            HandleIncomingAnnouncement(data);
            return;
        }

        std::string transfer_id = data.value("transfer_id", "");
        if (transfer_id.empty()) transfer_id = data.value("file_id", "");
        if (transfer_id.empty()) return;

        // Strict Matching: transfer_id + room
        std::lock_guard<std::mutex> lock(g_pendingMutex);
        if (g_pendingPushes.count(transfer_id)) {
            auto pending = g_pendingPushes[transfer_id];
            if (pending->room != room) return;

            if (event == "transfer_command") {
                std::string action = data.value("action", "");
                std::string reason = data.value("reason", "none");
                LOG_INFO("rx transfer_command: action=%s, reason=%s, id=%s", action.c_str(), reason.c_str(), transfer_id.c_str());
                
                if (action == "finish") {
                    pending->completed = true;
                } else if (action == "upload_relay") {
                    pending->upload_requested = true;
                }
            } else if (event == "file_sync_completed") {
                pending->completed = true;
            } else if (event == "file_need_relay") {
                pending->need_relay = true;
            }
        }
    });

    sio.Connect(data.relay_server_url, data.room_id, data.device_id);

    // Setup Clipboard Monitor
    ClipboardPush::Platform::ClipboardMonitor::Instance().SetCallback([]() {
        if (g_isProcessingRemoteSync) return;
        
        auto& config = Config::Instance().Data();
        if (!config.auto_push_text && !config.auto_push_image && !config.auto_push_file) return;

        LOG_INFO("Local clipboard changed, checking for auto-push...");
        auto cb = ClipboardPush::Platform::Clipboard::Get();
        
        if (cb.type == ClipboardPush::Platform::ClipboardType::Text && config.auto_push_text && !cb.text.empty()) {
            LOG_INFO("Auto-pushing text...");
            ClipboardPush::PushText(cb.text);
            ClipboardPush::ShowNotification(L"Auto Pushed", L"Text content sent automatically", ClipboardPush::UI::NotificationStyle::Outbound);
        } else if (cb.type == ClipboardPush::Platform::ClipboardType::Image && config.auto_push_image) {
            LOG_INFO("Auto-pushing image...");
            ClipboardPush::PushImage(cb.image_data);
            ClipboardPush::ShowNotification(L"Auto Pushed", L"Image content sent automatically", ClipboardPush::UI::NotificationStyle::Outbound);
        } else if (cb.type == ClipboardPush::Platform::ClipboardType::Files && config.auto_push_file) {
            LOG_INFO("Auto-pushing %zu file(s)...", cb.files.size());
            for (const auto& file : cb.files) {
                ClipboardPush::PushPhysicalFile(file);
            }
            ClipboardPush::ShowNotification(L"Auto Pushed", L"File(s) sent automatically", ClipboardPush::UI::NotificationStyle::Outbound);
        }
    });
    ClipboardPush::Platform::ClipboardMonitor::Instance().Start(hWnd);

    // Register Hotkey
    ClipboardPush::Platform::Hotkey::Instance().SetCallback([]() {
        LOG_INFO("Hotkey Triggered!");
        auto cb = ClipboardPush::Platform::Clipboard::Get();
        if (cb.type == ClipboardPush::Platform::ClipboardType::Text && !cb.text.empty()) {
            ClipboardPush::PushText(cb.text);
            ClipboardPush::ShowNotification(L"Clipboard Pushed", L"Text content sent", ClipboardPush::UI::NotificationStyle::Outbound);
        } else if (cb.type == ClipboardPush::Platform::ClipboardType::Image) {
            ClipboardPush::PushImage(cb.image_data);
            ClipboardPush::ShowNotification(L"Clipboard Pushed", L"Image content sent", ClipboardPush::UI::NotificationStyle::Outbound);
        } else if (cb.type == ClipboardPush::Platform::ClipboardType::Files) {
            for (const auto& file : cb.files) {
                ClipboardPush::PushPhysicalFile(file);
            }
            ClipboardPush::ShowNotification(L"Clipboard Pushed", L"File(s) sent", ClipboardPush::UI::NotificationStyle::Outbound);
        }
    });
    ClipboardPush::Platform::Hotkey::Instance().Register(hWnd, data.push_hotkey);

    // Show UI
    if (!data.start_minimized) {
        ClipboardPush::UI::MainWindow::Instance().Show();
    } else {
        ClipboardPush::ShowNotification(L"Clipboard Push v3.0", L"Running ultra-light in system tray!");
    }

    // Message Loop
    MSG msg = {};
    while (GetMessage(&msg, NULL, 0, 0)) {
        TranslateMessage(&msg);
        DispatchMessage(&msg);
    }

    ClipboardPush::Platform::ClipboardMonitor::Instance().Stop(hWnd);
    ClipboardPush::LocalServer::Instance().Stop();
    ClipboardPush::UI::TrayIcon::Instance().Remove();
    ClipboardPush::Platform::Shutdown();
    LOG_INFO("--- Application Terminated Gracefully ---");
    return 0;
}