#include "Application.h"
#include "core/Config.h"
#include "core/CryptoManager.h"
#include "core/Logger.h"
#include "network/SocketIOClient.h"
#include "network/HttpClient.h"
#include "platform/ClipboardManager.h"
#include "platform/HotkeyManager.h"
#include "ui/MainWindow.h"
#include "ui/SettingsWindow.h"
#include "ui/TrayIcon.h"

#include <QIcon>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QFileDialog>
#include <QJsonDocument>
#include <QJsonObject>
#include <QDateTime>
#include <QThread>
#include <QSettings>

#ifdef _WIN32
#include <windows.h>
#include <shellapi.h>
#include <shobjidl.h>
#endif

namespace ClipboardPush {

Application::Application(QApplication* app)
    : QObject(app)
    , m_app(app)
{
    m_app->setQuitOnLastWindowClosed(false);

    // Set Windows taskbar icon
#ifdef _WIN32
    SetCurrentProcessExplicitAppUserModelID(L"huang.clipboardman.cpp.client");
#endif

    // Initialize components
    m_config = std::make_unique<Config>(this);
    m_crypto = std::make_unique<CryptoManager>();
    m_socketClient = std::make_unique<SocketIOClient>(this);
    m_httpClient = std::make_unique<HttpClient>(this);
    m_clipboardManager = std::make_unique<ClipboardManager>(this);
    m_hotkeyManager = std::make_unique<HotkeyManager>(this);
    m_mainWindow = std::make_unique<MainWindow>();
    m_settingsWindow = std::make_unique<SettingsWindow>();
    m_trayIcon = std::make_unique<TrayIcon>(this);

    // Load config
    loadConfig();

    // Set up crypto
    if (!m_config->roomKey().isEmpty()) {
        m_crypto->setKey(m_config->roomKey());
    }

    // Set application icon
    QIcon icon(":/icon.png");
    m_app->setWindowIcon(icon);
    m_mainWindow->setWindowIcon(icon);
    m_settingsWindow->setWindowIcon(icon);
    m_trayIcon->setIcon(icon);

    // Setup connections
    setupConnections();

    // Start network connection
    m_httpClient->setBaseUrl(m_config->relayServerUrl());
    m_socketClient->setServerUrl(m_config->relayServerUrl());
    m_socketClient->setRoomCredentials(m_config->roomId(), m_config->deviceId());
    m_socketClient->connectToServer();

    // Setup hotkey
    restartHotkeyListener();

    // Setup auto-start
    updateAutoStartRegistration(m_config->autoStart());

    // Show UI
    if (!m_config->startMinimized()) {
        m_mainWindow->show();
    }
    m_trayIcon->show();
}

Application::~Application() {
    m_trayIcon->hide();
    m_socketClient->disconnect();
}

int Application::run() {
    return m_app->exec();
}

void Application::loadConfig() {
    m_config->load();
}

void Application::setupConnections() {
    // Socket.IO events
    connect(m_socketClient.get(), &SocketIOClient::connected,
            this, &Application::onConnected);
    connect(m_socketClient.get(), &SocketIOClient::disconnected,
            this, &Application::onDisconnected);
    connect(m_socketClient.get(), &SocketIOClient::clipboardReceived,
            this, &Application::onClipboardReceived);
    connect(m_socketClient.get(), &SocketIOClient::fileReceived,
            this, &Application::onFileReceived);

    // Main window
    connect(m_mainWindow.get(), &MainWindow::pushTextClicked,
            this, &Application::onManualTextPush);
    connect(m_mainWindow.get(), &MainWindow::settingsClicked,
            this, &Application::showSettings);

    // Settings window
    connect(m_settingsWindow.get(), &SettingsWindow::saveClicked,
            this, &Application::onSettingsSaved);
    connect(m_settingsWindow.get(), &SettingsWindow::reconnectClicked,
            this, &Application::onReconnectClicked);
    connect(m_settingsWindow.get(), &SettingsWindow::browseClicked,
            this, &Application::onBrowseClicked);
    connect(m_settingsWindow.get(), &SettingsWindow::pushClicked,
            this, &Application::onHotkeyTriggered);

    // Tray icon
    connect(m_trayIcon.get(), &TrayIcon::pushClicked,
            this, &Application::onHotkeyTriggered);
    connect(m_trayIcon.get(), &TrayIcon::showMainClicked,
            m_mainWindow.get(), &MainWindow::show);
    connect(m_trayIcon.get(), &TrayIcon::settingsClicked,
            this, &Application::showSettings);
    connect(m_trayIcon.get(), &TrayIcon::quitClicked,
            this, &Application::quit);
    connect(m_trayIcon.get(), &TrayIcon::activated,
            this, &Application::onTrayActivated);

    // Hotkey
    connect(m_hotkeyManager.get(), &HotkeyManager::hotkeyTriggered,
            this, &Application::onHotkeyTriggered);
}

void Application::updateStatus(const QString& text, const QString& color) {
    m_mainWindow->setStatus(text, color);
    m_settingsWindow->setStatus(text, color);
}

void Application::showSettings() {
    m_settingsWindow->setServerUrl(m_config->relayServerUrl());
    m_settingsWindow->setDownloadPath(m_config->downloadPath());
    m_settingsWindow->setHotkey(m_config->pushHotkey());
    m_settingsWindow->setAutoCopyImage(m_config->autoCopyImage());
    m_settingsWindow->setAutoCopyFile(m_config->autoCopyFile());
    m_settingsWindow->setAutoStart(m_config->autoStart());
    m_settingsWindow->setStartMinimized(m_config->startMinimized());

    // Generate QR code
    if (!m_config->roomId().isEmpty() && !m_config->roomKey().isEmpty()) {
        QJsonObject qrData;
        qrData["server"] = m_config->relayServerUrl();
        qrData["room"] = m_config->roomId();
        qrData["key"] = m_config->roomKey();

        QJsonDocument doc(qrData);
        m_settingsWindow->setQRContent(QString::fromUtf8(doc.toJson(QJsonDocument::Compact)));
    }

    // Update status
    if (m_socketClient->isConnected()) {
        m_settingsWindow->setStatus("Connected", "green");
    } else {
        m_settingsWindow->setStatus("Disconnected", "red");
    }

    m_settingsWindow->show();
    m_settingsWindow->raise();
    m_settingsWindow->activateWindow();
}

void Application::restartHotkeyListener() {
    QString hotkey = m_config->pushHotkey();
    if (!hotkey.isEmpty() && hotkey != "None") {
        m_hotkeyManager->registerHotkey(hotkey);
        LOG_INFO("Hotkey registered: {}", hotkey.toStdString());
    }
}

// Network event handlers
void Application::onConnected() {
    LOG_INFO("Connected to relay server");
    updateStatus("Connected", "green");
}

void Application::onDisconnected() {
    LOG_INFO("Disconnected from relay server");
    updateStatus("Disconnected", "red");
}

void Application::onClipboardReceived(const QString& content, bool isEncrypted) {
    try {
        QString finalText = content;

        if (isEncrypted) {
            if (!m_crypto->hasKey()) {
                LOG_ERROR("No encryption key set");
                return;
            }

            auto decrypted = m_crypto->decryptFromBase64(content);
            if (!decrypted) {
                LOG_ERROR("Failed to decrypt clipboard content");
                return;
            }
            finalText = *decrypted;
        }

        m_clipboardManager->setText(finalText);

        QString preview = finalText.left(50);
        if (finalText.length() > 50) preview += "...";
        m_trayIcon->showMessage("Clipboard Push",
                                QString("Received Text: %1").arg(preview));

        LOG_INFO("Clipboard received and set");
    } catch (const std::exception& e) {
        LOG_ERROR("Clipboard sync error: {}", e.what());
    }
}

void Application::onFileReceived(const QJsonObject& data) {
    QString url = data["download_url"].toString();
    QString filename = data["filename"].toString();
    QString fileType = data["type"].toString("file");

    if (url.isEmpty() || filename.isEmpty() || !m_crypto->hasKey()) {
        LOG_ERROR("Invalid file sync data or no encryption key");
        return;
    }

    LOG_INFO("Downloading file: {}", filename.toStdString());

    // Download in a separate thread to avoid blocking UI
    QThread* thread = QThread::create([this, url, filename, fileType]() {
        downloadAndSaveFile(url, filename, fileType);
    });
    connect(thread, &QThread::finished, thread, &QThread::deleteLater);
    thread->start();
}

void Application::downloadAndSaveFile(const QString& url, const QString& filename,
                                       const QString& fileType) {
    auto encData = m_httpClient->downloadFile(url);
    if (!encData) {
        LOG_ERROR("Failed to download file");
        return;
    }

    auto decData = m_crypto->decrypt(*encData);
    if (!decData) {
        LOG_ERROR("Failed to decrypt file");
        return;
    }

    // Ensure download directory exists
    QDir saveDir(m_config->downloadPath());
    if (!saveDir.exists()) {
        saveDir.mkpath(".");
    }

    // Generate unique filename
    QString localPath = saveDir.filePath(filename);
    QFileInfo fileInfo(localPath);
    int count = 1;
    while (QFile::exists(localPath)) {
        localPath = saveDir.filePath(
            QString("%1_%2.%3").arg(fileInfo.completeBaseName())
                               .arg(count++)
                               .arg(fileInfo.suffix())
        );
    }

    // Save file
    QFile file(localPath);
    if (!file.open(QIODevice::WriteOnly)) {
        LOG_ERROR("Failed to save file: {}", localPath.toStdString());
        return;
    }
    file.write(*decData);
    file.close();

    LOG_INFO("File saved: {}", localPath.toStdString());

    // Copy to clipboard (on main thread)
    QMetaObject::invokeMethod(this, [this, localPath, fileType]() {
        if (fileType == "image" && m_config->autoCopyImage()) {
            m_clipboardManager->setImageFromFile(localPath);
        } else if (m_config->autoCopyFile()) {
            m_clipboardManager->setFiles({localPath});
        }

        QFileInfo info(localPath);
        m_trayIcon->showMessage("Clipboard Push",
                                QString("Received File: %1").arg(info.fileName()));
    }, Qt::QueuedConnection);
}

// UI event handlers
void Application::onManualTextPush(const QString& text) {
    if (pushText(text)) {
        m_mainWindow->setStatus("Text Pushed Successfully", "green");
    } else {
        m_mainWindow->setStatus("Push Failed", "red");
    }
}

void Application::onSettingsSaved(const SettingsData& data) {
    // Update config
    if (!data.serverUrl.isEmpty()) {
        m_config->setRelayServerUrl(data.serverUrl);
    }
    m_config->setDownloadPath(data.downloadPath);
    m_config->setPushHotkey(data.pushHotkey);
    m_config->setAutoCopyImage(data.autoCopyImage);
    m_config->setAutoCopyFile(data.autoCopyFile);
    m_config->setAutoStart(data.autoStart);
    m_config->setStartMinimized(data.startMinimized);
    m_config->save();

    // Update auto-start registration
    updateAutoStartRegistration(data.autoStart);

    // Update crypto
    if (!m_config->roomKey().isEmpty()) {
        m_crypto->setKey(m_config->roomKey());
    }

    // Reconnect
    onReconnectClicked();

    // Restart hotkey
    restartHotkeyListener();

    // Refresh settings view
    showSettings();

    LOG_INFO("Settings updated");
}

void Application::onReconnectClicked() {
    LOG_INFO("Manual reconnection triggered");
    m_httpClient->setBaseUrl(m_config->relayServerUrl());
    m_socketClient->setServerUrl(m_config->relayServerUrl());
    m_socketClient->setRoomCredentials(m_config->roomId(), m_config->deviceId());
    m_socketClient->forceReconnect();
}

void Application::onBrowseClicked() {
    QString path = QFileDialog::getExistingDirectory(
        m_settingsWindow.get(),
        "Select Download Directory",
        m_config->downloadPath()
    );

    if (!path.isEmpty()) {
        m_settingsWindow->setDownloadPath(path);
    }
}

void Application::onHotkeyTriggered() {
    if (!m_crypto->hasKey() || m_config->roomId().isEmpty()) {
        LOG_WARN("Not paired, cannot push");
        return;
    }

    ClipboardContent content = m_clipboardManager->getContent();

    switch (content.type) {
        case ClipboardContentType::Files:
            for (const QString& path : content.files) {
                QFileInfo info(path);
                if (info.isFile()) {
                    pushFile(path);
                }
            }
            break;

        case ClipboardContentType::Image: {
            QString filename = QString("img_%1.png")
                .arg(QDateTime::currentSecsSinceEpoch());
            pushFileData(content.imageData, filename, "image");
            break;
        }

        case ClipboardContentType::Text:
            if (!content.text.trimmed().isEmpty()) {
                pushText(content.text);
            }
            break;

        default:
            LOG_DEBUG("No content to push");
            break;
    }
}

void Application::onTrayActivated() {
    m_mainWindow->showNormal();
    m_mainWindow->activateWindow();
}

void Application::quit() {
    m_hotkeyManager->unregisterHotkey();
    m_socketClient->disconnect();
    m_trayIcon->hide();
    m_settingsWindow->close();
    m_mainWindow->close();
    m_app->quit();
}

// Push operations
bool Application::pushText(const QString& text) {
    if (!m_crypto->hasKey()) {
        LOG_ERROR("No encryption key");
        return false;
    }

    auto encrypted = m_crypto->encryptToBase64(text);
    if (!encrypted) {
        LOG_ERROR("Failed to encrypt text");
        return false;
    }

    QString timestamp = QDateTime::currentDateTime().toString("HH:mm:ss");

    return m_httpClient->relayClipboard(
        m_config->roomId(),
        m_config->deviceId(),
        *encrypted,
        timestamp
    );
}

bool Application::pushFile(const QString& path) {
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly)) {
        LOG_ERROR("Failed to open file: {}", path.toStdString());
        return false;
    }

    QByteArray data = file.readAll();
    file.close();

    QFileInfo info(path);
    return pushFileData(data, info.fileName(), "file");
}

bool Application::pushFileData(const QByteArray& data, const QString& filename,
                               const QString& fileType) {
    if (!m_crypto->hasKey()) {
        LOG_ERROR("No encryption key");
        return false;
    }

    auto encrypted = m_crypto->encrypt(data);
    if (!encrypted) {
        LOG_ERROR("Failed to encrypt file");
        return false;
    }

    // Get upload auth
    auto auth = m_httpClient->requestUploadAuth(filename, encrypted->size());
    if (!auth.success) {
        LOG_ERROR("Failed to get upload auth: {}", auth.error.toStdString());
        return false;
    }

    // Upload file
    if (!m_httpClient->uploadFile(auth.uploadUrl, *encrypted)) {
        LOG_ERROR("Failed to upload file");
        return false;
    }

    // Relay file notification
    QString timestamp = QDateTime::currentDateTime().toString("HH:mm:ss");
    bool result = m_httpClient->relayFileSync(
        m_config->roomId(),
        m_config->deviceId(),
        auth.downloadUrl,
        filename,
        fileType,
        timestamp
    );

    if (result) {
        LOG_INFO("File pushed: {}", filename.toStdString());
    }

    return result;
}

void Application::updateAutoStartRegistration(bool enabled) {
#ifdef _WIN32
    QSettings settings("HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", QSettings::NativeFormat);
    QString appName = "ClipboardPush";
    QString appPath = QDir::toNativeSeparators(QCoreApplication::applicationFilePath());

    if (enabled) {
        settings.setValue(appName, "\"" + appPath + "\"");
        LOG_INFO("Auto-start registered: {}", appPath.toStdString());
    } else {
        settings.remove(appName);
        LOG_INFO("Auto-start unregistered");
    }
#endif
}

} // namespace ClipboardPush
