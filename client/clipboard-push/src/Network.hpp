#pragma once
#include "Config.hpp"
#include <sio_client.h>
#include <functional>
#include <vector>

class NetworkClient {
public:
    NetworkClient(const AppConfig& config);
    ~NetworkClient();

    void Start();
    void Stop();
    bool IsConnected() const;

    // 推送功能
    bool PushText(const std::string& text);
    bool PushFile(const std::string& filePath, const std::string& fileName);

private:
    void OnConnect();
    void OnDisconnect();
    void OnNewMessage(sio::event& ev);

    // 下载文件
    std::string DownloadFile(const std::string& url, const std::string& fileName);

    AppConfig m_config;
    sio::client m_io;
    bool m_connected = false;
};
