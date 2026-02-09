#include "ConfigManager.hpp"
#include "Common.hpp"
#include <fstream>
#include <nlohmann/json.hpp>
#include <random>

// Helper to generate random ID if needed
std::string GenerateDeviceID() {
  static const char alphanum[] = "0123456789abcdef";
  std::string tmp_s;
  tmp_s.reserve(8);
  std::random_device rd;
  std::mt19937 gen(rd());
  std::uniform_int_distribution<> dis(0, 15);

  for (int i = 0; i < 8; ++i) {
    tmp_s += alphanum[dis(gen)];
  }

  char user[256];
  DWORD len = 256;
  if (!GetUserNameA(user, &len))
    strcpy(user, "User");

  return "pc_" + std::string(user) + "_" + tmp_s;
}

// Helper for safe string extraction
std::string GetJsonString(const nlohmann::json &j, const std::string &key,
                          const std::string &def) {
  if (j.contains(key) && j[key].is_string()) {
    return j[key].get<std::string>();
  }
  return def;
}

ConfigManager &ConfigManager::Instance() {
  static ConfigManager instance;
  return instance;
}

bool ConfigManager::Load(const std::string &path) {
  try {
    if (!std::filesystem::exists(path)) {
      // Defaults
      m_config.device_id = GenerateDeviceID();
      return Save(path);
    }

    std::ifstream f(path);
    nlohmann::json j;
    f >> j;

    m_config.server_url = GetJsonString(j, "relay_server_url", "");
    if (m_config.server_url.empty()) {
      m_config.server_url =
          GetJsonString(j, "server_url", "http://localhost:5000");
    }

    m_config.download_path = GetJsonString(j, "download_path", "Downloads");
    m_config.device_id = GetJsonString(j, "device_id", GenerateDeviceID());
    m_config.room_id = GetJsonString(j, "room_id", "");
    m_config.room_key = GetJsonString(j, "room_key", "");
    m_config.push_hotkey = GetJsonString(j, "push_hotkey", "Alt+V");

    // Boolean and number types usually deduce correctly
    m_config.auto_copy_image = j.value("auto_copy_image", true);
    m_config.auto_copy_file = j.value("auto_copy_file", true);
    m_config.auto_start = j.value("auto_start", false);
    m_config.hotkey_mod = j.value("hotkey_mod", 0);
    m_config.hotkey_key = j.value("hotkey_key", 0);

    return true;
  } catch (const std::exception &e) {
    spdlog::error("Config load failed: {}", e.what());
    return false;
  }
}

bool ConfigManager::Save(const std::string &path) {
  try {
    nlohmann::json j;
    j["server_url"] = m_config.server_url;
    j["download_path"] = m_config.download_path;
    j["device_id"] = m_config.device_id;
    j["room_id"] = m_config.room_id;
    j["room_key"] = m_config.room_key;
    j["push_hotkey"] = m_config.push_hotkey;
    j["auto_copy_image"] = m_config.auto_copy_image;
    j["auto_copy_file"] = m_config.auto_copy_file;
    j["auto_start"] = m_config.auto_start;
    j["hotkey_mod"] = m_config.hotkey_mod;
    j["hotkey_key"] = m_config.hotkey_key;

    std::ofstream f(path);
    f << j.dump(4);
    return true;
  } catch (const std::exception &e) {
    spdlog::error("Config save failed: {}", e.what());
    return false;
  }
}
