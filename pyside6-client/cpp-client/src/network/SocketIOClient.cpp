#include "SocketIOClient.h"
#include "core/Logger.h"

#include <QJsonDocument>
#include <QJsonArray>
#include <QUrl>
#include <QUrlQuery>

namespace ClipboardPush {

SocketIOClient::SocketIOClient(QObject* parent)
    : QObject(parent)
{
    connect(&m_webSocket, &QWebSocket::connected, this, &SocketIOClient::onConnected);
    connect(&m_webSocket, &QWebSocket::disconnected, this, &SocketIOClient::onDisconnected);
    connect(&m_webSocket, &QWebSocket::textMessageReceived, this, &SocketIOClient::onTextMessageReceived);
    
    // Explicitly use the overload for errorOccurred in Qt6
    connect(&m_webSocket, &QWebSocket::errorOccurred, [this](QAbstractSocket::SocketError error) {
        this->onError(error);
    });

    m_reconnectTimer.setInterval(5000);
    connect(&m_reconnectTimer, &QTimer::timeout, this, &SocketIOClient::onReconnectTimer);
}

SocketIOClient::~SocketIOClient() {
    m_manuallyDisconnected = true;
    m_webSocket.close();
}

void SocketIOClient::setServerUrl(const QString& url) {
    m_serverUrl = url;
}

void SocketIOClient::setRoomCredentials(const QString& roomId, const QString& clientId) {
    m_roomId = roomId;
    m_clientId = clientId;
}

void SocketIOClient::connectToServer() {
    if (m_serverUrl.isEmpty()) return;
    
    m_manuallyDisconnected = false;
    
    // Convert HTTP URL to WebSocket URL
    QUrl url(m_serverUrl);
    if (url.scheme() == "http") url.setScheme("ws");
    else if (url.scheme() == "https") url.setScheme("wss");
    
    // Add Socket.IO parameters
    url.setPath("/socket.io/");
    QUrlQuery query;
    query.addQueryItem("EIO", "4");
    query.addQueryItem("transport", "websocket");
    url.setQuery(query);
    
    LOG_INFO("Connecting to WebSocket: {}", url.toString().toStdString());
    m_webSocket.open(url);
}

void SocketIOClient::disconnect() {
    m_manuallyDisconnected = true;
    m_reconnectTimer.stop();
    m_webSocket.close();
}

void SocketIOClient::forceReconnect() {
    disconnect();
    connectToServer();
}

bool SocketIOClient::isConnected() const {
    return m_connected;
}

void SocketIOClient::onConnected() {
    LOG_INFO("WebSocket Connected");
    // Socket.IO Handshake: send '40' to open the engine.io session on the default namespace
    sendPacket("40");
}

void SocketIOClient::onDisconnected() {
    LOG_INFO("WebSocket Disconnected");
    m_connected = false;
    emit disconnected();
    
    if (!m_manuallyDisconnected) {
        m_reconnectTimer.start();
    }
}

void SocketIOClient::onTextMessageReceived(const QString& message) {
    // Socket.IO Packet format: <engine.io type><socket.io type>[<data>]
    // Engine.IO types: 0: open, 1: close, 2: ping, 3: pong, 4: message
    // Socket.IO types: 0: connect, 1: disconnect, 2: event, 3: ack, 4: error
    
    if (message.isEmpty()) return;
    
    char engineType = message[0].toLatin1();
    
    if (engineType == '2') { // Ping
        sendPacket("3"); // Pong
    } else if (engineType == '4') { // Message
        handleSocketIOPacket(message.mid(1));
    } else if (engineType == '0') { // Open
        LOG_DEBUG("Engine.IO session opened");
    }
}

void SocketIOClient::handleSocketIOPacket(const QString& packet) {
    if (packet.isEmpty()) return;
    
    char socketType = packet[0].toLatin1();
    QString data = packet.mid(1);
    
    if (socketType == '0') { // Connect
        LOG_INFO("Socket.IO Session Connected");
        m_connected = true;
        emit connected();
        joinRoom();
    } else if (socketType == '2') { // Event
        QJsonDocument doc = QJsonDocument::fromJson(data.toUtf8());
        if (doc.isArray()) {
            QJsonArray arr = doc.array();
            QString eventName = arr.at(0).toString();
            QJsonObject eventData = arr.at(1).toObject();
            
            if (eventName == "clipboard_sync") {
                QString content = eventData["content"].toString();
                bool encrypted = eventData["encrypted"].toBool(false);
                emit clipboardReceived(content, encrypted);
            } else if (eventName == "file_sync") {
                emit fileReceived(eventData);
            }
        }
    }
}

void SocketIOClient::sendPacket(const QString& packet) {
    if (m_webSocket.isValid()) {
        m_webSocket.sendTextMessage(packet);
    }
}

void SocketIOClient::joinRoom() {
    if (m_roomId.isEmpty()) return;
    
    QJsonArray args;
    args.append("join");
    QJsonObject joinData;
    joinData["room"] = m_roomId;
    joinData["client_id"] = m_clientId;
    args.append(joinData);
    
    // 42 is Message type (4) + Event type (2)
    sendPacket("42" + QString::fromUtf8(QJsonDocument(args).toJson(QJsonDocument::Compact)));
}

void SocketIOClient::onError(QAbstractSocket::SocketError error) {
    QString errorStr = m_webSocket.errorString();
    LOG_ERROR("WebSocket Error: {}", errorStr.toStdString());
    emit errorOccurred(errorStr);
}

void SocketIOClient::onReconnectTimer() {
    if (!m_connected && !m_manuallyDisconnected) {
        connectToServer();
    }
}

} // namespace ClipboardPush
