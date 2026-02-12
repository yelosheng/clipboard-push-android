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
    m_lastActivityTime = std::chrono::duration_cast<std::chrono::seconds>(std::chrono::system_clock::now().time_since_epoch()).count();
    
    SetStatus(ConnectionStatus::Connecting);
    StartWatchdog();
    
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

void SocketIOService::SetCallbacks(ClipboardCallback onClipboard, FileCallback onFile, StatusCallback onStatus, CountdownCallback onCountdown) {
    m_onClipboard = onClipboard;
    m_onFile = onFile;
    m_onStatus = onStatus;
    m_onCountdown = onCountdown;
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
        for (int i = 5; i > 0; --i) {
            bool isConnected = (m_status == ConnectionStatus::ConnectedLonely || m_status == ConnectionStatus::ConnectedSynced);
            if (m_manuallyStopped || isConnected) return;
            
            if (m_onCountdown) m_onCountdown(i);
            std::this_thread::sleep_for(std::chrono::seconds(1));
        }
        
        bool isConnected = (m_status == ConnectionStatus::ConnectedLonely || m_status == ConnectionStatus::ConnectedSynced);
        if (!m_manuallyStopped && !isConnected) {
            LOG_INFO("Reconnection timer expired, trying to connect...");
            Connect(m_serverUrl, m_roomId, m_clientId);
        }
    }).detach();
}

void SocketIOService::OnMessage(const std::string& message) {
    if (message.empty()) return;

    LOG_DEBUG("WS Msg: %s", message.c_str());
    m_lastActivityTime = std::chrono::duration_cast<std::chrono::seconds>(std::chrono::system_clock::now().time_since_epoch()).count();

    char engineType = message[0];
    if (engineType == '2') { // Ping
        SendPacket("3"); // Pong
    } else if (engineType == '4') { // Message
        HandlePacket(message.substr(1));
    }
}

void SocketIOService::StartWatchdog() {
    if (m_watchdogRunning) return;
    m_watchdogRunning = true;

    std::thread([this]() {
        while (!m_manuallyStopped) {
            std::this_thread::sleep_for(std::chrono::seconds(10));
            if (m_manuallyStopped) break;

            uint64_t now = std::chrono::duration_cast<std::chrono::seconds>(std::chrono::system_clock::now().time_since_epoch()).count();
            
            // If connected but no activity for 45 seconds, force reconnect
            bool isConnected = (m_status == ConnectionStatus::ConnectedLonely || m_status == ConnectionStatus::ConnectedSynced);
            if (isConnected && (now - m_lastActivityTime > 45)) {
                LOG_INFO("Watchdog: Connection is silent for too long. Forcing reconnect.");
                m_ws.Close(); // This will trigger Disconnect callback and ScheduleReconnect
            }
        }
        m_watchdogRunning = false;
    }).detach();
}

void SocketIOService::HandlePacket(const std::string& packet) {
    if (packet.empty()) return;

    LOG_DEBUG("SIO Pkt: %s", packet.c_str());
    char socketType = packet[0];
    if (socketType == '0') { // Connect success
        LOG_INFO("Socket.IO Connected");
        SetStatus(ConnectionStatus::ConnectedLonely); // Default to lonely until update
        JoinRoom();
    } else if (socketType == '2') { // Event
        try {
            auto j = nlohmann::json::parse(packet.substr(1));
            if (j.is_array() && j.size() >= 2) {
                std::string eventName = j[0];
                auto eventData = j[1];
                
                if (eventName == "clipboard_sync") {
                    // If we receive a sync, we are definitely connected to someone
                    SetStatus(ConnectionStatus::ConnectedSynced);
                    if (m_onClipboard) {
                        m_onClipboard(eventData.value("content", ""), eventData.value("encrypted", false));
                    }
                } else if (eventName == "file_sync") {
                    SetStatus(ConnectionStatus::ConnectedSynced);
                    if (m_onFile) m_onFile(eventData);
                } else if (eventName == "room_stats") {
                    int count = eventData.value("count", 1);
                    if (count > 1) SetStatus(ConnectionStatus::ConnectedSynced);
                    else SetStatus(ConnectionStatus::ConnectedLonely);
                }
            }
        } catch (...) {
            LOG_ERROR("Failed to parse event packet");
        }
    }
}

void SocketIOService::JoinRoom() {
    if (m_roomId.empty()) return;
    
    nlohmann::json data;
    data["room"] = m_roomId;
    data["client_id"] = m_clientId;
    data["client_type"] = "windows";
    
    // 42 is Message type (4) + Event type (2)
    SendPacket("42" + nlohmann::json({"join", data}).dump());
    LOG_INFO("Joined room: %s", m_roomId.c_str());
}

void SocketIOService::SendPacket(const std::string& packet) {
    m_ws.Send(packet);
}

}
