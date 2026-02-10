#pragma once
#include "Network.h"
#include <string>
#include <functional>
#include <nlohmann/json.hpp>

namespace ClipboardPush {

enum class ConnectionStatus {
    Disconnected,
    Connecting,
    Connected,
    Retrying
};

class SocketIOService {
public:
    using ClipboardCallback = std::function<void(const std::string& content, bool encrypted)>;
    using FileCallback = std::function<void(const nlohmann::json& data)>;
    using StatusCallback = std::function<void(ConnectionStatus status)>;

    static SocketIOService& Instance();

    void Connect(const std::string& serverUrl, const std::string& roomId, const std::string& clientId);
    void Disconnect();
    
    void SetCallbacks(ClipboardCallback onClipboard, FileCallback onFile, StatusCallback onStatus);

private:
    SocketIOService();
    void OnMessage(const std::string& message);
    void HandlePacket(const std::string& packet);
    void JoinRoom();
    void SendPacket(const std::string& packet);
    void SetStatus(ConnectionStatus status);
    void ScheduleReconnect();

    Network::WebSocketClient m_ws;
    std::string m_serverUrl;
    std::string m_roomId;
    std::string m_clientId;
    ConnectionStatus m_status = ConnectionStatus::Disconnected;
    bool m_manuallyStopped = false;

    ClipboardCallback m_onClipboard;
    FileCallback m_onFile;
    StatusCallback m_onStatus;
};

}
