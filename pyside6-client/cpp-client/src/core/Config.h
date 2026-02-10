#pragma once

#include <QString>
#include <QObject>
#include <nlohmann/json.hpp>
#include <string>
#include <optional>

namespace ClipboardPush {

class Config : public QObject {
    Q_OBJECT

public:
    explicit Config(QObject* parent = nullptr);
    ~Config() = default;

    bool load(const QString& path = QString());
    bool save();

    // Getters
    QString relayServerUrl() const { return m_relayServerUrl; }
    QString downloadPath() const { return m_downloadPath; }
    QString deviceId() const { return m_deviceId; }
    QString roomId() const { return m_roomId; }
    QString roomKey() const { return m_roomKey; }
    QString pushHotkey() const { return m_pushHotkey; }
    bool autoCopyImage() const { return m_autoCopyImage; }
    bool autoCopyFile() const { return m_autoCopyFile; }
    bool autoStart() const { return m_autoStart; }
    bool startMinimized() const { return m_startMinimized; }

    // Setters
    void setRelayServerUrl(const QString& url);
    void setDownloadPath(const QString& path);
    void setDeviceId(const QString& id);
    void setRoomId(const QString& id);
    void setRoomKey(const QString& key);
    void setPushHotkey(const QString& hotkey);
    void setAutoCopyImage(bool enabled);
    void setAutoCopyFile(bool enabled);
    void setAutoStart(bool enabled);
    void setStartMinimized(bool enabled);

    // Utility
    bool isValid() const;
    QString configPath() const { return m_configPath; }

    // Generate new room credentials
    void generateNewRoom();

signals:
    void configChanged();

private:
    void setDefaults();
    static QString generateRoomId();
    static QString generateRoomKey();
    static QString getDefaultDownloadPath();
    static QString generateDeviceId();

    QString m_configPath;
    QString m_relayServerUrl;
    QString m_downloadPath;
    QString m_deviceId;
    QString m_roomId;
    QString m_roomKey;
    QString m_pushHotkey;
    bool m_autoCopyImage = true;
    bool m_autoCopyFile = true;
    bool m_autoStart = false;
    bool m_startMinimized = false;
};

} // namespace ClipboardPush
