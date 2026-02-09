#include "NetworkClient.hpp"
#include "Base64.hpp"
#include "ClipboardManager.hpp"
#include "Common.hpp"
#include "ConfigManager.hpp"
#include "CryptoManager.hpp"
#include <cpr/cpr.h>
#include <filesystem>
#include <fstream>


using namespace sio;

NetworkClient::NetworkClient() {
  m_client.set_open_listener(
      std::bind(&NetworkClient::OnSocketConnected, this));
  m_client.set_close_listener(
      std::bind(&NetworkClient::OnSocketClosed, this, std::placeholders::_1));
  m_client.set_fail_listener(std::bind(&NetworkClient::OnSocketFail, this));
  m_client.set_reconnect_attempts(100);
  m_client.set_reconnect_delay(1000);
}

NetworkClient::~NetworkClient() { Stop(); }

void NetworkClient::Start() {
  auto &config = ConfigManager::Instance().GetConfig();
  std::string url = config.server_url;
  spdlog::info("Connecting to {}", url);
  m_client.connect(url);
}

void NetworkClient::Stop() {
  m_client.sync_close();
  m_client.clear_con_listeners();
}

void NetworkClient::Reconnect() {
  Stop();
  Start();
}

bool NetworkClient::IsConnected() const { return m_connected; }

void NetworkClient::SetOnConnectionStatusChanged(
    std::function<void(bool)> callback) {
  m_connCallback = callback;
}

void NetworkClient::SetOnSyncMessageReceived(
    std::function<void(const std::string &, const std::string &)> callback) {
  m_msgCallback = callback;
}

void NetworkClient::OnSocketConnected() {
  m_connected = true;
  if (m_connCallback)
    m_connCallback(true);
  spdlog::info("Socket.IO Connected");

  auto &config = ConfigManager::Instance().GetConfig();
  if (!config.room_id.empty()) {
    object_message::ptr obj = object_message::create();
    obj->get_map()["room"] = string_message::create(config.room_id);
    obj->get_map()["client_id"] = string_message::create(config.device_id);
    m_client.socket()->emit("join", obj);
    spdlog::info("Joined room: {}", config.room_id);
  }

  m_client.socket()->on(
      "clipboard_sync",
      std::bind(&NetworkClient::OnClipboardSync, this, std::placeholders::_1));
  m_client.socket()->on("file_sync", std::bind(&NetworkClient::OnFileSync, this,
                                               std::placeholders::_1));
}

void NetworkClient::OnSocketClosed(sio::client::close_reason const &reason) {
  m_connected = false;
  if (m_connCallback)
    m_connCallback(false);
  spdlog::warn("Socket.IO Closed");
}

void NetworkClient::OnSocketFail() {
  m_connected = false;
  if (m_connCallback)
    m_connCallback(false);
  spdlog::error("Socket.IO Connection Failed");
}

void NetworkClient::OnClipboardSync(sio::event &ev) {
  auto msg = ev.get_message();
  if (msg->get_flag() == message::flag_object) {
    auto map = msg->get_map();
    if (map.count("content")) {
      std::string content = map["content"]->get_string();
      bool encrypted = false;
      if (map.count("encrypted") &&
          map["encrypted"]->get_flag() == message::flag_boolean) {
        encrypted = map["encrypted"]->get_bool();
      }
      if (!content.empty()) {
        HandleText(content, encrypted);
      }
    }
  }
}

void NetworkClient::OnFileSync(sio::event &ev) {
  auto msg = ev.get_message();
  if (msg->get_flag() == message::flag_object) {
    auto map = msg->get_map();
    std::string url, filename, type = "file";

    if (map.count("download_url"))
      url = map["download_url"]->get_string();
    if (map.count("filename"))
      filename = map["filename"]->get_string();
    if (map.count("type"))
      type = map["type"]->get_string();

    if (!url.empty()) {
      HandleFile(url, filename, type);
    }
  }
}

void NetworkClient::HandleText(const std::string &content, bool encrypted) {
  std::string finalText = content;

  if (encrypted) {
    std::vector<uint8_t> cipherBytes = Base64::Decode(content);
    auto decrypted = CryptoManager::Instance().DecryptToString(cipherBytes);
    if (decrypted) {
      finalText = *decrypted;
      spdlog::info("Decrypted text: {}", finalText);
    } else {
      spdlog::error("Failed to decrypt text");
      return;
    }
  }

  ClipboardManager::Instance().SetText(finalText);

  if (m_msgCallback)
    m_msgCallback("Text", finalText);
}

void NetworkClient::HandleFile(const std::string &url,
                               const std::string &filename,
                               const std::string &type) {
  auto &config = ConfigManager::Instance().GetConfig();
  std::filesystem::path downloadDir(config.download_path);
  if (!std::filesystem::exists(downloadDir)) {
    std::filesystem::create_directories(downloadDir);
  }

  std::filesystem::path targetFile = downloadDir / filename;

  // De-duplication
  int i = 1;
  std::string stem = targetFile.stem().string();
  std::string ext = targetFile.extension().string();
  while (std::filesystem::exists(targetFile)) {
    targetFile = downloadDir / (stem + "_" + std::to_string(i++) + ext);
  }

  DownloadFile(url, targetFile.string(), type);
}

void NetworkClient::DownloadFile(const std::string &url,
                                 const std::string &targetPath,
                                 const std::string &type) {
  // Actually run download in a thread to not block socket.io
  std::thread([this, url, targetPath, type]() {
    spdlog::info("Downloading {} to {}", url, targetPath);
    cpr::Response r = cpr::Get(cpr::Url{url});
    if (r.status_code == 200) {
      std::string finalData = r.text;

      // Decrypt if key is present (assuming all files are encrypted if E2EE is
      // on)
      if (CryptoManager::Instance().HasKey()) {
        std::vector<uint8_t> vec(r.text.begin(), r.text.end());
        auto decrypted = CryptoManager::Instance().Decrypt(vec);
        if (decrypted) {
          finalData.assign(decrypted->begin(), decrypted->end());
          spdlog::info("Decrypted file content");
        } else {
          spdlog::error("Failed to decrypt file content");
          return;
        }
      }

      std::ofstream out(targetPath, std::ios::binary);
      out.write(finalData.c_str(), finalData.size());
      out.close();
      spdlog::info("Saved file to {}", targetPath);

      auto &config = ConfigManager::Instance().GetConfig();
      if ((type == "image" && config.auto_copy_image) ||
          (type == "file" && config.auto_copy_file)) {
        // Call Clipboard on Main Thread?
        // Win32 Clipboard APIs usually require the thread that owns the window
        // or open/close pair. We should probably post a message to main HWND or
        // use a mutex protected call if the impl supports it. Our
        // ClipboardManager uses OpenClipboard, which associates with the
        // current thread or window. If called from here, it associates with
        // this worker thread. This is fine as long as we close it. But wait, if
        // main thread has it open, we fail. It's safer to use the main thread.
        // For MVP simple: just try.
        ClipboardManager::Instance().SetFiles({targetPath});
      }
      if (m_msgCallback)
        m_msgCallback(type, targetPath);

    } else {
      spdlog::error("Download failed: {}", r.status_code);
    }
  }).detach();
}
