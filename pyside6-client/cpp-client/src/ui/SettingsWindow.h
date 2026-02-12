#pragma once

#include <QMainWindow>
#include <QLineEdit>
#include <QCheckBox>
#include <QPushButton>
#include <QLabel>

namespace ClipboardPush {

class HotkeyRecorderEdit;
class QRCodeWidget;

struct SettingsData {
    QString serverUrl;
    QString downloadPath;
    QString pushHotkey;
    bool autoCopyImage = true;
    bool autoCopyFile = true;
    bool autoStart = false;
    bool startMinimized = false;
};

class SettingsWindow : public QMainWindow {
    Q_OBJECT

public:
    explicit SettingsWindow(QWidget* parent = nullptr);
    ~SettingsWindow() = default;

    void setServerUrl(const QString& url);
    void setDownloadPath(const QString& path);
    void setHotkey(const QString& hotkey);
    void setAutoCopyImage(bool enabled);
    void setAutoCopyFile(bool enabled);
    void setAutoStart(bool enabled);
    void setStartMinimized(bool enabled);
    void setQRContent(const QString& content);
    void setStatus(const QString& text, const QString& color = "#666");

    QString downloadPath() const;

signals:
    void saveClicked(const SettingsData& data);
    void reconnectClicked();
    void browseClicked();
    void pushClicked();

private slots:
    void onSaveClicked();

private:
    void setupUi();

    QLineEdit* m_serverUrlInput = nullptr;
    QLineEdit* m_downloadPathInput = nullptr;
    HotkeyRecorderEdit* m_hotkeyInput = nullptr;
    QCheckBox* m_cbImages = nullptr;
    QCheckBox* m_cbFiles = nullptr;
    QCheckBox* m_cbStartup = nullptr;
    QCheckBox* m_cbStartMinimized = nullptr;
    QPushButton* m_browseBtn = nullptr;
    QPushButton* m_saveBtn = nullptr;
    QPushButton* m_pushBtn = nullptr;
    QPushButton* m_reconnectBtn = nullptr;
    QPushButton* m_closeBtn = nullptr;
    QLabel* m_statusLabel = nullptr;
    QRCodeWidget* m_qrWidget = nullptr;
};

} // namespace ClipboardPush
