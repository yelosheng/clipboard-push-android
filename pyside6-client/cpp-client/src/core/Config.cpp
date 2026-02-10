#include "Config.h"
#include "Logger.h"
#include "CryptoManager.h"

#include <QFile>
#include <QDir>
#include <QStandardPaths>
#include <QCoreApplication>
#include <QJsonDocument>
#include <QJsonObject>
#include <chrono>
#include <random>

#ifdef _WIN32
#include <windows.h>
#include <lmcons.h>
#endif

namespace ClipboardPush {

Config::Config(QObject* parent)
    : QObject(parent)
{
    setDefaults();
}

void Config::setDefaults() {
    m_relayServerUrl = "http://kxkl.tk:5055";
    m_downloadPath = getDefaultDownloadPath();
    m_deviceId = generateDeviceId();
    m_roomId = QString();
    m_roomKey = QString();
    m_pushHotkey = "Ctrl+F6";
    m_autoCopyImage = true;
    m_autoCopyFile = true;
    m_autoStart = false;
}

QString Config::getDefaultDownloadPath() {
    QString downloadDir = QStandardPaths::writableLocation(QStandardPaths::DownloadLocation);
    return QDir(downloadDir).filePath("ClipboardMan");
}

QString Config::generateDeviceId() {
    QString username = "user";

#ifdef _WIN32
    WCHAR buffer[UNLEN + 1];
    DWORD size = UNLEN + 1;
    if (GetUserNameW(buffer, &size)) {
        username = QString::fromWCharArray(buffer);
    }
#endif

    auto now = std::chrono::system_clock::now();
    auto epoch = now.time_since_epoch();
    auto seconds = std::chrono::duration_cast<std::chrono::seconds>(epoch).count();

    return QString("pc_%1_%2").arg(username).arg(seconds % 10000);
}

QString Config::generateRoomId() {
    auto now = std::chrono::system_clock::now();
    auto epoch = now.time_since_epoch();
    auto seconds = std::chrono::duration_cast<std::chrono::seconds>(epoch).count();

    return QString("room_%1").arg(seconds);
}

QString Config::generateRoomKey() {
    // Generate a 256-bit key and return as base64
    return CryptoManager::generateKeyBase64();
}

void Config::generateNewRoom() {
    m_roomId = generateRoomId();
    m_roomKey = generateRoomKey();
    emit configChanged();
}

bool Config::load(const QString& path) {
    m_configPath = path.isEmpty()
        ? QDir(QCoreApplication::applicationDirPath()).filePath("config.json")
        : path;

    QFile file(m_configPath);
    if (!file.exists()) {
        LOG_INFO("Config file not found, initializing new pairing session");
        generateNewRoom();
        return save();
    }

    if (!file.open(QIODevice::ReadOnly)) {
        LOG_ERROR("Failed to open config file: {}", m_configPath.toStdString());
        return false;
    }

    QByteArray data = file.readAll();
    file.close();

    QJsonParseError error;
    QJsonDocument doc = QJsonDocument::fromJson(data, &error);
    if (error.error != QJsonParseError::NoError) {
        LOG_ERROR("Failed to parse config JSON: {}", error.errorString().toStdString());
        return false;
    }

    QJsonObject obj = doc.object();

    // Load with defaults fallback
    m_relayServerUrl = obj.value("relay_server_url").toString(m_relayServerUrl);
    m_downloadPath = obj.value("download_path").toString(m_downloadPath);
    m_deviceId = obj.value("device_id").toString(m_deviceId);
    m_roomId = obj.value("room_id").toString();
    m_roomKey = obj.value("room_key").toString();
    m_pushHotkey = obj.value("push_hotkey").toString(m_pushHotkey);
    m_autoCopyImage = obj.value("auto_copy_image").toBool(m_autoCopyImage);
    m_autoCopyFile = obj.value("auto_copy_file").toBool(m_autoCopyFile);
    m_autoStart = obj.value("auto_start").toBool(m_autoStart);

    // Generate room if missing
    if (m_roomId.isEmpty() || m_roomKey.isEmpty()) {
        generateNewRoom();
        save();
    }

    LOG_INFO("Config loaded from: {}", m_configPath.toStdString());
    return true;
}

bool Config::save() {
    if (m_configPath.isEmpty()) {
        m_configPath = QDir(QCoreApplication::applicationDirPath()).filePath("config.json");
    }

    QJsonObject obj;
    obj["relay_server_url"] = m_relayServerUrl;
    obj["download_path"] = m_downloadPath;
    obj["device_id"] = m_deviceId;
    obj["room_id"] = m_roomId;
    obj["room_key"] = m_roomKey;
    obj["push_hotkey"] = m_pushHotkey;
    obj["auto_copy_image"] = m_autoCopyImage;
    obj["auto_copy_file"] = m_autoCopyFile;
    obj["auto_start"] = m_autoStart;

    QJsonDocument doc(obj);

    QFile file(m_configPath);
    if (!file.open(QIODevice::WriteOnly)) {
        LOG_ERROR("Failed to open config file for writing: {}", m_configPath.toStdString());
        return false;
    }

    file.write(doc.toJson(QJsonDocument::Indented));
    file.close();

    LOG_INFO("Config saved to: {}", m_configPath.toStdString());
    return true;
}

bool Config::isValid() const {
    return !m_relayServerUrl.isEmpty()
        && !m_roomId.isEmpty()
        && !m_roomKey.isEmpty();
}

void Config::setRelayServerUrl(const QString& url) {
    if (m_relayServerUrl != url) {
        m_relayServerUrl = url;
        emit configChanged();
    }
}

void Config::setDownloadPath(const QString& path) {
    if (m_downloadPath != path) {
        m_downloadPath = path;
        emit configChanged();
    }
}

void Config::setDeviceId(const QString& id) {
    if (m_deviceId != id) {
        m_deviceId = id;
        emit configChanged();
    }
}

void Config::setRoomId(const QString& id) {
    if (m_roomId != id) {
        m_roomId = id;
        emit configChanged();
    }
}

void Config::setRoomKey(const QString& key) {
    if (m_roomKey != key) {
        m_roomKey = key;
        emit configChanged();
    }
}

void Config::setPushHotkey(const QString& hotkey) {
    if (m_pushHotkey != hotkey) {
        m_pushHotkey = hotkey;
        emit configChanged();
    }
}

void Config::setAutoCopyImage(bool enabled) {
    if (m_autoCopyImage != enabled) {
        m_autoCopyImage = enabled;
        emit configChanged();
    }
}

void Config::setAutoCopyFile(bool enabled) {
    if (m_autoCopyFile != enabled) {
        m_autoCopyFile = enabled;
        emit configChanged();
    }
}

void Config::setAutoStart(bool enabled) {
    if (m_autoStart != enabled) {
        m_autoStart = enabled;
        emit configChanged();
    }
}

} // namespace ClipboardPush
