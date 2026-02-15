#include "Config.h"
#include "Utils.h"
#include "Logger.h"
#include "Crypto.h"
#include <fstream>
#include <filesystem>
#include <shlobj.h>
#include <lmcons.h>

namespace ClipboardPush {

Config& Config::Instance() {
    static Config instance;
    return instance;
}

std::string Config::GetConfigPath() {
    if (m_configPath.empty()) {
        wchar_t buffer[MAX_PATH];
        GetModuleFileNameW(NULL, buffer, MAX_PATH);
        std::filesystem::path path(buffer);
        m_configPath = (path.parent_path() / "config.json").string();
    }
    return m_configPath;
}

void Config::InitializeDefaults() {
    // Downloads Path
    if (m_data.download_path.empty()) {
        PWSTR path = NULL;
        if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_Downloads, 0, NULL, &path))) {
            std::filesystem::path downloadDir(path);
            m_data.download_path = (downloadDir / "ClipboardMan").string();
            CoTaskMemFree(path);
        }
    }
    
    // Device ID
    if (m_data.device_id.empty()) {
        wchar_t buffer[UNLEN + 1];
        DWORD size = UNLEN + 1;
        std::wstring username = L"user";
        if (GetUserNameW(buffer, &size)) {
            username = buffer;
        }
        m_data.device_id = "pc_" + Utils::ToUtf8(username) + "_win32";
    }

    // Generate room credentials if not already present
    if (m_data.room_id.empty() || m_data.room_key.empty()) {
        GenerateNewCredentials();
    }
}

void Config::GenerateNewCredentials() {
    m_data.room_id = "room_" + std::to_string(std::chrono::system_clock::to_time_t(std::chrono::system_clock::now()));
    m_data.room_key = Crypto::GenerateKeyBase64();
    // Also regenerate device ID to ensure clean slate
    wchar_t buffer[UNLEN + 1];
    DWORD size = UNLEN + 1;
    std::wstring username = L"user";
    if (GetUserNameW(buffer, &size)) username = buffer;
    
    auto now = std::chrono::system_clock::now();
    auto seconds = std::chrono::duration_cast<std::chrono::seconds>(now.time_since_epoch()).count();
    m_data.device_id = "pc_" + Utils::ToUtf8(username) + "_" + std::to_string(seconds % 10000);
    
    Save();
    LOG_INFO("Credentials reset. New Room: %s", m_data.room_id.c_str());
}

bool Config::Load() {
    std::string path = GetConfigPath();
    std::ifstream file(path);
    if (!file.is_open()) {
        InitializeDefaults();
        return Save(); // Create new
    }
    
    try {
        nlohmann::json j;
        file >> j;
        
        m_data.relay_server_url = j.value("relay_server_url", m_data.relay_server_url);
        m_data.download_path = j.value("download_path", m_data.download_path);
        m_data.device_id = j.value("device_id", m_data.device_id);
        m_data.room_id = j.value("room_id", m_data.room_id);
        m_data.room_key = j.value("room_key", m_data.room_key);
        m_data.push_hotkey = j.value("push_hotkey", m_data.push_hotkey);
        m_data.auto_copy_image = j.value("auto_copy_image", m_data.auto_copy_image);
        m_data.auto_copy_file = j.value("auto_copy_file", m_data.auto_copy_file);
        m_data.auto_push_text = j.value("auto_push_text", false);
        m_data.auto_push_image = j.value("auto_push_image", false);
        m_data.auto_push_file = j.value("auto_push_file", false);
        m_data.auto_start = j.value("auto_start", m_data.auto_start);
        m_data.start_minimized = j.value("start_minimized", m_data.start_minimized);
        m_data.show_notifications = j.value("show_notifications", true);
        
        // Generate credentials if missing
        if (m_data.room_id.empty() || m_data.room_key.empty()) {
            LOG_INFO("Credentials missing, generating new room...");
            if (m_data.room_id.empty()) m_data.room_id = "room_" + std::to_string(std::chrono::system_clock::to_time_t(std::chrono::system_clock::now()));
            if (m_data.room_key.empty()) m_data.room_key = Crypto::GenerateKeyBase64();
            Save();
        }

        if (m_data.device_id.empty()) InitializeDefaults();
        
        LOG_INFO("Config loaded from %s", path.c_str());
        return true;
    } catch (const std::exception& e) {
        LOG_ERROR("Failed to load config: %s", e.what());
        return false;
    }
}

bool Config::Save() {
    std::string path = GetConfigPath();
    nlohmann::json j;
    j["relay_server_url"] = m_data.relay_server_url;
    j["download_path"] = m_data.download_path;
    j["device_id"] = m_data.device_id;
    j["room_id"] = m_data.room_id;
    j["room_key"] = m_data.room_key;
    j["push_hotkey"] = m_data.push_hotkey;
    j["auto_copy_image"] = m_data.auto_copy_image;
    j["auto_copy_file"] = m_data.auto_copy_file;
    j["auto_push_text"] = m_data.auto_push_text;
    j["auto_push_image"] = m_data.auto_push_image;
    j["auto_push_file"] = m_data.auto_push_file;
    j["auto_start"] = m_data.auto_start;
    j["start_minimized"] = m_data.start_minimized;
    j["show_notifications"] = m_data.show_notifications;
    j["lan_timeout"] = m_data.lan_timeout;
    
    std::ofstream file(path);
    if (file.is_open()) {
        file << j.dump(4);
        return true;
    }
    return false;
}

}
