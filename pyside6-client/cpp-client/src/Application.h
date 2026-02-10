#pragma once

#include <QObject>
#include <QApplication>
#include <memory>

namespace ClipboardPush {

class Config;
class CryptoManager;
class SocketIOClient;
class HttpClient;
class ClipboardManager;
class HotkeyManager;
class MainWindow;
class SettingsWindow;
class TrayIcon;
struct SettingsData;

class Application : public QObject {
    Q_OBJECT

public:
    explicit Application(QApplication* app);
    ~Application();

    int run();

private slots:
    // Network events
    void onConnected();
    void onDisconnected();
    void onClipboardReceived(const QString& content, bool isEncrypted);
    void onFileReceived(const QJsonObject& data);

    // UI events
    void onManualTextPush(const QString& text);
    void onSettingsSaved(const SettingsData& data);
    void onReconnectClicked();
    void onBrowseClicked();
    void onHotkeyTriggered();
    void onTrayActivated();

    // App lifecycle
    void quit();

private:
    void setupConnections();
    void loadConfig();
    void updateStatus(const QString& text, const QString& color);
    void showSettings();
    void restartHotkeyListener();
    void updateAutoStartRegistration(bool enabled);

    // Push operations
    bool pushText(const QString& text);
    bool pushFile(const QString& path);
    bool pushFileData(const QByteArray& data, const QString& filename, const QString& fileType);

    // Download operations
    void downloadAndSaveFile(const QString& url, const QString& filename, const QString& fileType);

    QApplication* m_app = nullptr;
    std::unique_ptr<Config> m_config;
    std::unique_ptr<CryptoManager> m_crypto;
    std::unique_ptr<SocketIOClient> m_socketClient;
    std::unique_ptr<HttpClient> m_httpClient;
    std::unique_ptr<ClipboardManager> m_clipboardManager;
    std::unique_ptr<HotkeyManager> m_hotkeyManager;
    std::unique_ptr<MainWindow> m_mainWindow;
    std::unique_ptr<SettingsWindow> m_settingsWindow;
    std::unique_ptr<TrayIcon> m_trayIcon;
};

} // namespace ClipboardPush
