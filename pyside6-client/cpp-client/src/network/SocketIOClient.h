#pragma once

#include <QObject>
#include <QString>
#include <QJsonObject>
#include <QtWebSockets/QWebSocket>
#include <QTimer>

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

private slots:
    void onConnected();
    void onDisconnected();
    void onTextMessageReceived(const QString& message);
    void onError(QAbstractSocket::SocketError error);
    void onReconnectTimer();

private:
    void sendPacket(const QString& packet);
    void handleSocketIOPacket(const QString& packet);
    void joinRoom();

    QWebSocket m_webSocket;
    QString m_serverUrl;
    QString m_roomId;
    QString m_clientId;
    bool m_connected = false;
    bool m_manuallyDisconnected = false;
    
    QTimer m_reconnectTimer;
};

} // namespace ClipboardPush