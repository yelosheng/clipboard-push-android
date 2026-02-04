#pragma once
#include <fstream>
#include <string>
#include <filesystem>
#include <nlohmann/json.hpp>
#include <spdlog/spdlog.h>

struct AppConfig {
    std::string server_url = "http://localhost:9661";
    std::string download_path = "C:/Downloads/ClipboardMan";
    bool auto_copy_image = true;
    bool auto_copy_file = true;

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
            } else {
                spdlog::warn("Config file not found, using defaults.");
            }
        } catch (const std::exception& e) {
            spdlog::error("Failed to load config: {}", e.what());
        }
        return config;
    }
};
