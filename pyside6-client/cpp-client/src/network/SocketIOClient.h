#pragma once

#include <QObject>
#include <QString>
#include <QJsonObject>
#include <memory>
#include <atomic>
#include <thread>

// Forward declarations for socket.io
namespace sio {
    class client;
    class message;
}

namespace ClipboardPush {

class SocketIOClient : public QObject {
    Q_OBJECT

public:
    explicit SocketIOClient(QObject* parent = nullptr);
    ~SocketIOClient();

    void setServerUrl(const QString& url);
    void setRoomCredentials(const QString& roomId, const QString& clientId);

    void connectToServer();
    void disconnect();
    void forceReconnect();

    bool isConnected() const;

signals:
    void connected();
    void disconnected();
    void clipboardReceived(const QString& content, bool isEncrypted);
    void fileReceived(const QJsonObject& data);
    void errorOccurred(const QString& error);

private:
    void setupEventHandlers();
    void joinRoom();

    std::unique_ptr<sio::client> m_client;
    QString m_serverUrl;
    QString m_roomId;
    QString m_clientId;
    std::atomic<bool> m_connected{false};
};

} // namespace ClipboardPush
