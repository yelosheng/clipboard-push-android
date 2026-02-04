#include "Network.hpp"
#include "Clipboard.hpp"
#include <ixwebsocket/IXNetSystem.h>
#include <cpr/cpr.h>
#include <spdlog/spdlog.h>
#include <nlohmann/json.hpp>
#include <filesystem>
#include <regex>

using json = nlohmann::json;

NetworkClient::NetworkClient(const AppConfig& config) : m_config(config) {
    // 必须调用以初始化 Windows Sockets
    ix::initNetSystem();

    m_socket.setOnMessageCallback([this](const ix::WebSocketMessagePtr& msg) {
        this->OnMessage(msg);
    });
}

NetworkClient::~NetworkClient() {
    Stop();
    ix::uninitNetSystem();
}

void NetworkClient::Start() {
    m_running = true;
    
    // 每次启动前重新构造 URL，确保使用的是最新配置
    std::string url = m_config.server_url;
    if (url.find("http://") == 0) url.replace(0, 7, "ws://");
    else if (url.find("https://") == 0) url.replace(0, 8, "wss://");
    
    if (!url.empty() && url.back() == '/') url.pop_back();
    url += "/socket.io/?EIO=4&transport=websocket";
    
    spdlog::info("Starting NetworkClient with URL: {}", url);
    m_socket.setUrl(url);
    m_socket.start();
}

void NetworkClient::Stop() {
    m_running = false;
    m_socket.stop();
}

void NetworkClient::Restart() {
    spdlog::info("Restarting NetworkClient...");
    Stop();
    Start();
}

bool NetworkClient::IsConnected() const {
    return m_connected;
}

void NetworkClient::OnMessage(const ix::WebSocketMessagePtr& msg) {
    if (msg->type == ix::WebSocketMessageType::Open) {
        spdlog::info("WebSocket Open. Sending Socket.IO connect packet...");
        m_connected = true;
        m_socket.send("40");
        if (m_onStatusChange) m_onStatusChange(true);
    }
    else if (msg->type == ix::WebSocketMessageType::Close) {
        spdlog::warn("Disconnected");
        m_connected = false;
        if (m_onStatusChange) m_onStatusChange(false);
    }
    else if (msg->type == ix::WebSocketMessageType::Message) {
        // spdlog::debug("Raw Message: {}", msg->str); // 调试用
        HandleSocketIOMessage(msg->str);
    }
    else if (msg->type == ix::WebSocketMessageType::Error) {
        spdlog::error("Connection error: {}", msg->errorInfo.reason);
    }
}

void NetworkClient::HandleSocketIOMessage(const std::string& payload) {
    // Socket.IO 协议简易解析
    // 0: open
    // 2: ping -> 需要回 pong (3)
    // 40: connection request
    // 42: event message ["event", data]

    if (payload.empty()) return;

    char type = payload[0];

    if (type == '0') { // Open
        // 收到 {"sid":...}，需要回复 40
        // 但对于单纯接收端，通常可以直接忽略
    }
    else if (type == '2') { // Ping
        m_socket.send("3"); // Pong
    }
    else if (type == '4') { 
        if (payload.size() > 1 && payload[1] == '2') { // 42: Event
            try {
                // 提取 JSON 部分: 42["new_message",{...}]
                std::string jsonStr = payload.substr(2);
                auto j = json::parse(jsonStr);

                if (j.is_array() && j.size() >= 2) {
                    std::string eventName = j[0];
                    if (eventName == "new_message") {
                        auto data = j[1];
                        
                        std::string msgType = data.value("type", "unknown");
                        std::string source = data.value("source", "unknown");

                        if (source == "pc_client_cpp") return;

                        spdlog::info("Received: Type={}, Source={}", msgType, source);

                        if (msgType == "text") {
                            std::string content = data.value("content", "");
                            Clipboard::SetText(content);
                            spdlog::info("Copied text: {}", content);
                        }
                        else if (msgType == "image" || msgType == "file") {
                            std::string fileUrl = data.value("file_url", "");
                            std::string fileName = data.value("file_name", "downloaded_file");
                            
                            if (fileUrl.find("http") != 0) {
                                fileUrl = m_config.server_url + fileUrl;
                            }
                            
                            std::string localPath = DownloadFile(fileUrl, fileName);
                            if (!localPath.empty()) {
                                Clipboard::SetFiles({localPath});
                                spdlog::info("Downloaded: {}", localPath);
                            }
                        }
                    }
                }
            }
            catch (const std::exception& e) {
                spdlog::error("JSON parse error: {}", e.what());
            }
        }
    }
}

std::string NetworkClient::DownloadFile(const std::string& url, const std::string& fileName) {
    try {
        std::filesystem::path dir(m_config.download_path);
        if (!std::filesystem::exists(dir)) std::filesystem::create_directories(dir);
        
        std::filesystem::path path = dir / fileName;
        int i = 1;
        while (std::filesystem::exists(path)) {
            path = dir / (std::to_string(i++) + "_" + fileName);
        }

        spdlog::info("Downloading {}...", fileName);
        std::ofstream f(path, std::ios::binary);
        cpr::Response r = cpr::Download(f, cpr::Url{url});
        
        if (r.status_code == 200) return path.string();
        else spdlog::error("Download failed: {}", r.status_code);
    } catch (const std::exception& e) {
        spdlog::error("Download exception: {}", e.what());
    }
    return "";
}

bool NetworkClient::PushText(const std::string& text) {
    auto r = cpr::Post(cpr::Url{m_config.server_url + "/api/push/text"}, 
                       cpr::Body{json{{"content", text}, {"source", "pc_client_cpp"}}.dump()},
                       cpr::Header{{"Content-Type", "application/json"}});
    return r.status_code == 200;
}

bool NetworkClient::PushFile(const std::string& filePath, const std::string& fileName) {
    auto r = cpr::Post(cpr::Url{m_config.server_url + "/api/push/file"},
                       cpr::Multipart{
                           {"file", cpr::File(filePath, fileName)},
                           {"source", "pc_client_cpp"}
                       });
    return r.status_code == 200;
}