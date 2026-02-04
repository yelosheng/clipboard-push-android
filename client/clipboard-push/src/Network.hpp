#pragma once
#include "Config.hpp"
#include <ixwebsocket/IXWebSocket.h>
#include <ixwebsocket/IXUserAgent.h>
#include <functional>
#include <string>
#include <thread>
#include <atomic>

class NetworkClient {
public:
    NetworkClient(const AppConfig& config);
    ~NetworkClient();

    void Start();
    void Stop();
    bool IsConnected() const;

    bool PushText(const std::string& text);
    bool PushFile(const std::string& filePath, const std::string& fileName);

private:
    void OnMessage(const ix::WebSocketMessagePtr& msg);
    void HandleSocketIOMessage(const std::string& payload);
    std::string DownloadFile(const std::string& url, const std::string& fileName);

    AppConfig m_config;
    ix::WebSocket m_socket;
    std::atomic<bool> m_connected{false};
    std::thread m_pingThread;
    std::atomic<bool> m_running{false};
};