#include "SocketIOClient.h"
#include "core/Logger.h"

#include <sio_client.h>
#include <QJsonDocument>
#include <QMetaObject>

namespace ClipboardPush {

SocketIOClient::SocketIOClient(QObject* parent)
    : QObject(parent)
    , m_client(std::make_unique<sio::client>())
{
    setupEventHandlers();
}

SocketIOClient::~SocketIOClient() {
    disconnect();
}

void SocketIOClient::setServerUrl(const QString& url) {
    m_serverUrl = url;
}

void SocketIOClient::setRoomCredentials(const QString& roomId, const QString& clientId) {
    m_roomId = roomId;
    m_clientId = clientId;
}

void SocketIOClient::setupEventHandlers() {
    // Connection opened
    m_client->set_open_listener([this]() {
        LOG_INFO("Socket.IO connected");
        m_connected = true;

        // Thread-safe signal emission
        QMetaObject::invokeMethod(this, [this]() {
            emit connected();
            joinRoom();
        }, Qt::QueuedConnection);
    });

    // Connection closed
    m_client->set_close_listener([this](sio::client::close_reason const& reason) {
        LOG_INFO("Socket.IO disconnected");
        m_connected = false;

        QMetaObject::invokeMethod(this, [this]() {
            emit disconnected();
        }, Qt::QueuedConnection);
    });

    // Connection failed
    m_client->set_fail_listener([this]() {
        LOG_ERROR("Socket.IO connection failed");
        m_connected = false;

        QMetaObject::invokeMethod(this, [this]() {
            emit errorOccurred("Connection failed");
            emit disconnected();
        }, Qt::QueuedConnection);
    });

    // Socket events
    m_client->set_socket_open_listener([this](const std::string& nsp) {
        // Register event handlers on default namespace
        auto socket = m_client->socket();

        // clipboard_sync event
        socket->on("clipboard_sync", [this](sio::event& ev) {
            try {
                auto msg = ev.get_message();
                if (msg->get_flag() != sio::message::flag_object) {
                    return;
                }

                auto obj = msg->get_map();
                std::string content;
                bool isEncrypted = false;

                auto contentIt = obj.find("content");
                if (contentIt != obj.end() && contentIt->second->get_flag() == sio::message::flag_string) {
                    content = contentIt->second->get_string();
                }

                auto encryptedIt = obj.find("encrypted");
                if (encryptedIt != obj.end() && encryptedIt->second->get_flag() == sio::message::flag_boolean) {
                    isEncrypted = encryptedIt->second->get_bool();
                }

                if (!content.empty()) {
                    QString qContent = QString::fromStdString(content);
                    QMetaObject::invokeMethod(this, [this, qContent, isEncrypted]() {
                        emit clipboardReceived(qContent, isEncrypted);
                    }, Qt::QueuedConnection);
                }
            } catch (const std::exception& e) {
                LOG_ERROR("Error handling clipboard_sync: {}", e.what());
            }
        });

        // file_sync event
        socket->on("file_sync", [this](sio::event& ev) {
            try {
                auto msg = ev.get_message();
                if (msg->get_flag() != sio::message::flag_object) {
                    return;
                }

                auto obj = msg->get_map();
                QJsonObject jsonData;

                // Extract fields
                auto urlIt = obj.find("download_url");
                if (urlIt != obj.end() && urlIt->second->get_flag() == sio::message::flag_string) {
                    jsonData["download_url"] = QString::fromStdString(urlIt->second->get_string());
                }

                auto filenameIt = obj.find("filename");
                if (filenameIt != obj.end() && filenameIt->second->get_flag() == sio::message::flag_string) {
                    jsonData["filename"] = QString::fromStdString(filenameIt->second->get_string());
                }

                auto typeIt = obj.find("type");
                if (typeIt != obj.end() && typeIt->second->get_flag() == sio::message::flag_string) {
                    jsonData["type"] = QString::fromStdString(typeIt->second->get_string());
                }

                QMetaObject::invokeMethod(this, [this, jsonData]() {
                    emit fileReceived(jsonData);
                }, Qt::QueuedConnection);
            } catch (const std::exception& e) {
                LOG_ERROR("Error handling file_sync: {}", e.what());
            }
        });
    });
}

void SocketIOClient::joinRoom() {
    if (!m_connected || m_roomId.isEmpty()) {
        return;
    }

    auto socket = m_client->socket();

    // Create join message
    auto msg = sio::object_message::create();
    msg->get_map()["room"] = sio::string_message::create(m_roomId.toStdString());
    msg->get_map()["client_id"] = sio::string_message::create(m_clientId.toStdString());

    socket->emit("join", msg);
    LOG_INFO("Joined room: {}", m_roomId.toStdString());
}

void SocketIOClient::connectToServer() {
    if (m_serverUrl.isEmpty()) {
        LOG_ERROR("Cannot connect: no server URL set");
        return;
    }

    if (m_connected) {
        LOG_DEBUG("Already connected");
        return;
    }

    LOG_INFO("Connecting to: {}", m_serverUrl.toStdString());

    // Enable auto-reconnect
    m_client->set_reconnect_attempts(0);  // Infinite retries
    m_client->set_reconnect_delay(5000);  // 5 seconds
    m_client->set_reconnect_delay_max(10000);

    try {
        m_client->connect(m_serverUrl.toStdString());
    } catch (const std::exception& e) {
        LOG_ERROR("Connection error: {}", e.what());
        QMetaObject::invokeMethod(this, [this, msg = QString::fromUtf8(e.what())]() {
            emit errorOccurred(msg);
        }, Qt::QueuedConnection);
    }
}

void SocketIOClient::disconnect() {
    if (m_client) {
        m_client->sync_close();
        m_connected = false;
    }
}

void SocketIOClient::forceReconnect() {
    LOG_INFO("Forcing reconnection...");
    disconnect();
    connectToServer();
}

bool SocketIOClient::isConnected() const {
    return m_connected;
}

} // namespace ClipboardPush
