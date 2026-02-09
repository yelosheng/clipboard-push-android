#pragma once
#include <fstream>
#include <string>
#include <filesystem>
#include <nlohmann/json.hpp>
#include <spdlog/spdlog.h>

namespace fs = std::filesystem;

struct AppConfig {
    std::string server_url = "http://localhost:9661";
    std::string download_path = "C:/Downloads/ClipboardMan";
    bool auto_copy_image = true;
    bool auto_copy_file = true;
    
    // Hotkey defaults: Ctrl + Alt + V
    int hotkey_mod = 2 | 1; // MOD_CONTROL (2) | MOD_ALT (1)
    int hotkey_key = 'V';
    bool auto_start = false;

    static AppConfig Load(const std::string& path = "config.json") {
        AppConfig config;
        try {
            if (std::filesystem::exists(path)) {
                std::ifstream f(path);
                nlohmann::json j = nlohmann::json::parse(f);
                
                if (j.contains("server_url")) config.server_url = j["server_url"];
                if (j.contains("download_path")) config.download_path = j["download_path"];
                if (j.contains("auto_copy_image")) config.auto_copy_image = j["auto_copy_image"];
                if (j.contains("auto_copy_file")) config.auto_copy_file = j["auto_copy_file"];
                
                if (j.contains("hotkey_mod")) config.hotkey_mod = j["hotkey_mod"];
                if (j.contains("hotkey_key")) config.hotkey_key = j["hotkey_key"];
                if (j.contains("auto_start")) config.auto_start = j["auto_start"];
            } else {
                spdlog::warn("Config file not found, using defaults.");
            }
        } catch (const std::exception& e) {
            spdlog::error("Failed to load config: {}", e.what());
        }
        return config;
    }
};
