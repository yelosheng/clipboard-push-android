#include "Network.hpp"
#include "Clipboard.hpp"
#include <cpr/cpr.h>
#include <spdlog/spdlog.h>
#include <filesystem>

NetworkClient::NetworkClient(const AppConfig& config) : m_config(config) {
    m_io.set_open_listener(std::bind(&NetworkClient::OnConnect, this));
    m_io.set_close_listener(std::bind(&NetworkClient::OnDisconnect, this));
    m_io.socket()->on("new_message", std::bind(&NetworkClient::OnNewMessage, this, std::placeholders::_1));
}

NetworkClient::~NetworkClient() {
    Stop();
}

void NetworkClient::Start() {
    spdlog::info("Connecting to server: {}", m_config.server_url);
    m_io.connect(m_config.server_url);
}

void NetworkClient::Stop() {
    m_io.sync_close();
}

bool NetworkClient::IsConnected() const {
    return m_connected;
}

void NetworkClient::OnConnect() {
    m_connected = true;
    spdlog::info("Connected to server!");
}

void NetworkClient::OnDisconnect() {
    m_connected = false;
    spdlog::warn("Disconnected from server.");
}

void NetworkClient::OnNewMessage(sio::event& ev) {
    try {
        auto data = ev.get_message();
        if (data->get_flag() != sio::message::flag_object) return;

        auto map = data->get_map();
        std::string type = map["type"]->get_string();
        std::string source = map.count("source") ? map["source"]->get_string() : "unknown";

        if (source == "pc_client_cpp") return; // Ignore self

        spdlog::info("Received message: Type={}, Source={}", type, source);

        if (type == "text") {
            std::string content = map["content"]->get_string();
            Clipboard::SetText(content);
            spdlog::info("Copied text to clipboard");
        } 
        else if (type == "image" || type == "file" || type == "video") {
            std::string fileUrl = map["file_url"]->get_string();
            std::string fileName = map["file_name"]->get_string();

            if (fileUrl.rfind("http", 0) != 0) {
                fileUrl = m_config.server_url + fileUrl;
            }

            std::string localPath = DownloadFile(fileUrl, fileName);
            if (!localPath.empty()) {
                Clipboard::SetFiles({localPath});
                spdlog::info("Downloaded and copied file: {}", localPath);
            }
        }
    } catch (const std::exception& e) {
        spdlog::error("Error processing message: {}", e.what());
    }
}

std::string NetworkClient::DownloadFile(const std::string& url, const std::string& fileName) {
    try {
        std::filesystem::path dir(m_config.download_path);
        std::filesystem::create_directories(dir);
        
        std::filesystem::path path = dir / fileName;
        
        // Simple avoid overwrite
        int i = 1;
        while (std::filesystem::exists(path)) {
            path = dir / (std::to_string(i++) + "_" + fileName);
        }

        spdlog::info("Downloading {} to {}", url, path.string());
        std::ofstream f(path, std::ios::binary);
        cpr::Response r = cpr::Download(f, cpr::Url{url});
        
        if (r.status_code == 200) {
            return path.string();
        } else {
            spdlog::error("Download failed code: {}", r.status_code);
        }
    } catch (const std::exception& e) {
        spdlog::error("Download exception: {}", e.what());
    }
    return "";
}

bool NetworkClient::PushText(const std::string& text) {
    std::string url = m_config.server_url + "/api/push/text";
    nlohmann::json j;
    j["content"] = text;
    j["source"] = "pc_client_cpp";

    auto r = cpr::Post(cpr::Url{url}, 
                       cpr::Body{j.dump()}, 
                       cpr::Header{{"Content-Type", "application/json"}});
    
    return r.status_code == 200;
}

bool NetworkClient::PushFile(const std::string& filePath, const std::string& fileName) {
    std::string url = m_config.server_url + "/api/push/file";
    
    auto r = cpr::Post(cpr::Url{url},
                       cpr::Multipart{
                           {"file", cpr::File(filePath, fileName)},
                           {"source", "pc_client_cpp"}
                       });
    return r.status_code == 200;
}
