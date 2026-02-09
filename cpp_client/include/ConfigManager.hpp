#pragma once
#include <nlohmann/json.hpp>
#include <string>
#include <vector>


struct AppConfig {
  std::string server_url = "http://localhost:5000";
  std::string download_path;
  std::string device_id;
  std::string room_id;
  std::string room_key;
  bool auto_copy_image = true;
  bool auto_copy_file = true;
  bool auto_start = false;
  int hotkey_mod = 0;
  int hotkey_key = 0;
};

class ConfigManager {
public:
  static ConfigManager &Instance();

  bool Load(const std::string &path = "config.json");
  bool Save(const std::string &path = "config.json");

  AppConfig &GetConfig() { return m_config; }
  void SetConfig(const AppConfig &config) { m_config = config; }

private:
  ConfigManager() = default;
  AppConfig m_config;
};
