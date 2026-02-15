#pragma once
#include <string>
#include <nlohmann/json.hpp>

namespace ClipboardPush {

struct ConfigData {
    std::string relay_server_url = "https://kxkl.tk:12505/";
    std::string download_path;
    std::string device_id;
    std::string room_id;
    std::string room_key;
    std::string push_hotkey = "Ctrl+F6";
    bool auto_copy_image = true;
    bool auto_copy_file = true;
    bool auto_push_text = false;
    bool auto_push_image = false;
    bool auto_push_file = false;
    bool auto_start = false;
    bool start_minimized = false;
    bool show_notifications = true;
    int lan_timeout = 10;
};

class Config {
public:
    static Config& Instance();
    
    bool Load();
    bool Save();
    
    ConfigData& Data() { return m_data; }
    
    // Set default download path and device ID if empty
    void InitializeDefaults();
    
    // Reset room ID and Key
    void GenerateNewCredentials();

private:
    Config() = default;
    ConfigData m_data;
    std::string m_configPath;
    
    std::string GetConfigPath();
};

}
