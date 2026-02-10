#include "SocketIOService.h"
#include "Logger.h"
#include <thread>
#include <chrono>

namespace ClipboardPush {

SocketIOService& SocketIOService::Instance() {
    static SocketIOService instance;
    return instance;
}

SocketIOService::SocketIOService() {
    m_ws.SetCallbacks(
        [this]() { 
            LOG_INFO("WS Connected, sending handshake...");
            SendPacket("40"); // Socket.IO Connect
        },
        [this](const std::string& msg) { OnMessage(msg); },
        [this]() { 
            LOG_INFO("WS Disconnected");
            SetStatus(ConnectionStatus::Disconnected);
            ScheduleReconnect();
        },
        [this](const std::string& err) {
            LOG_ERROR("WS Error: %s", err.c_str());
            SetStatus(ConnectionStatus::Disconnected);
            ScheduleReconnect();
        }
    );
}

void SocketIOService::Connect(const std::string& url, const std::string& roomId, const std::string& clientId) {
    m_serverUrl = url;
    m_roomId = roomId;
    m_clientId = clientId;
    m_manuallyStopped = false;
    
    SetStatus(ConnectionStatus::Connecting);
    
    // Convert http to ws, https to wss
    std::string wsUrl = url;
    size_t pos = wsUrl.find("http");
    if (pos == 0) {
        if (wsUrl.size() > 5 && wsUrl[4] == 's') wsUrl.replace(0, 5, "wss");
        else wsUrl.replace(0, 4, "ws");
    }
    
    // Append Socket.IO path
    if (wsUrl.back() == '/') wsUrl.pop_back();
    wsUrl += "/socket.io/?EIO=4&transport=websocket";
    
    LOG_INFO("Connecting to %s", wsUrl.c_str());
    m_ws.Connect(wsUrl);
}

void SocketIOService::Disconnect() {
    m_manuallyStopped = true;
    m_ws.Close();
    SetStatus(ConnectionStatus::Disconnected);
}

void SocketIOService::SetCallbacks(ClipboardCallback onClipboard, FileCallback onFile, StatusCallback onStatus) {
    m_onClipboard = onClipboard;
    m_onFile = onFile;
    m_onStatus = onStatus;
}

void SocketIOService::SetStatus(ConnectionStatus status) {
    m_status = status;
    if (m_onStatus) m_onStatus(status);
}

void SocketIOService::ScheduleReconnect() {
    if (m_manuallyStopped) return;
    
    SetStatus(ConnectionStatus::Retrying);
    
    // Create a background thread to wait and reconnect
    std::thread([this]() {
        LOG_INFO("Reconnecting in 5 seconds...");
        std::this_thread::sleep_for(std::chrono::seconds(5));
        if (!m_manuallyStopped && m_status != ConnectionStatus::Connected) {
            Connect(m_serverUrl, m_roomId, m_clientId);
        }
    }).detach();
}

void SocketIOService::OnMessage(const std::string& message) {
    if (message.empty()) return;

    char engineType = message[0];
    if (engineType == '2') { // Ping
        SendPacket("3"); // Pong
    } else if (engineType == '4') { // Message
        HandlePacket(message.substr(1));
    }
}

void SocketIOService::HandlePacket(const std::string& packet) {
    if (packet.empty()) return;

    char socketType = packet[0];
    if (socketType == '0') { // Connect success
        LOG_INFO("Socket.IO Connected");
        SetStatus(ConnectionStatus::Connected);
        JoinRoom();
    } else if (socketType == '2') { // Event
        try {
            auto j = nlohmann::json::parse(packet.substr(1));
            if (j.is_array() && j.size() >= 2) {
                std::string eventName = j[0];
                auto eventData = j[1];
                
                if (eventName == "clipboard_sync") {
                    if (m_onClipboard) {
                        m_onClipboard(eventData.value("content", ""), eventData.value("encrypted", false));
                    }
                } else if (eventName == "file_sync") {
                    if (m_onFile) m_onFile(eventData);
                }
            }
        } catch (...) {
            LOG_ERROR("Failed to parse event packet");
        }
    }
}

void SocketIOService::JoinRoom() {
    if (m_roomId.empty()) return;
    
    nlohmann::json j = nlohmann::json::array();
    j.push_back("join");
    nlohmann::json data;
    data["room"] = m_roomId;
    data["client_id"] = m_clientId;
    j.push_back(data);
    
    SendPacket("42" + j.dump());
    LOG_INFO("Joined room: %s", m_roomId.c_str());
}

void SocketIOService::SendPacket(const std::string& packet) {
    m_ws.Send(packet);
}

}
