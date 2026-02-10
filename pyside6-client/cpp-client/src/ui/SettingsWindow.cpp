#include "SettingsWindow.h"
#include "widgets/HotkeyRecorderEdit.h"
#include "widgets/QRCodeWidget.h"

#include <QWidget>
#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QFrame>
#include <QFont>

namespace ClipboardPush {

SettingsWindow::SettingsWindow(QWidget* parent)
    : QMainWindow(parent)
{
    setWindowTitle("Clipboard Push - Settings");
    resize(650, 450);
    setupUi();
}

void SettingsWindow::setupUi() {
    QWidget* centralWidget = new QWidget(this);
    setCentralWidget(centralWidget);

    QHBoxLayout* mainLayout = new QHBoxLayout(centralWidget);
    mainLayout->setContentsMargins(20, 20, 20, 20);
    mainLayout->setSpacing(30);

    // Left side: Settings
    QVBoxLayout* leftLayout = new QVBoxLayout();
    leftLayout->setSpacing(15);

    // Server URL (hidden)
    m_serverUrlInput = new QLineEdit(this);
    m_serverUrlInput->setPlaceholderText("http://your-server:5055");
    m_serverUrlInput->hide();

    // Download Path
    leftLayout->addWidget(new QLabel("Download Path:", this));
    QHBoxLayout* pathLayout = new QHBoxLayout();
    m_downloadPathInput = new QLineEdit(this);
    pathLayout->addWidget(m_downloadPathInput);

    m_browseBtn = new QPushButton("...", this);
    m_browseBtn->setFixedWidth(40);
    connect(m_browseBtn, &QPushButton::clicked, this, &SettingsWindow::browseClicked);
    pathLayout->addWidget(m_browseBtn);

    leftLayout->addLayout(pathLayout);

    // Push Hotkey
    leftLayout->addWidget(new QLabel("Push Hotkey (Click and press keys):", this));
    m_hotkeyInput = new HotkeyRecorderEdit(this);
    leftLayout->addWidget(m_hotkeyInput);

    // Checkboxes
    m_cbImages = new QCheckBox("Auto Copy Received Images", this);
    m_cbFiles = new QCheckBox("Auto Copy Received Files", this);
    m_cbStartup = new QCheckBox("Start on Boot", this);
    leftLayout->addWidget(m_cbImages);
    leftLayout->addWidget(m_cbFiles);
    leftLayout->addWidget(m_cbStartup);

    leftLayout->addStretch();

    // Status
    m_statusLabel = new QLabel("Status: Disconnected", this);
    m_statusLabel->setStyleSheet("color: #666;");
    leftLayout->addWidget(m_statusLabel);

    leftLayout->addStretch();

    // Right side: QR Code
    QVBoxLayout* rightLayout = new QVBoxLayout();
    rightLayout->setAlignment(Qt::AlignTop | Qt::AlignHCenter);

    rightLayout->addWidget(new QLabel("Scan to Pair Mobile", this));
    m_qrWidget = new QRCodeWidget(this);
    rightLayout->addWidget(m_qrWidget);

    rightLayout->addSpacing(20);

    // Buttons
    QVBoxLayout* btnLayout = new QVBoxLayout();
    btnLayout->setSpacing(10);

    m_saveBtn = new QPushButton("Save Settings", this);
    m_saveBtn->setFixedHeight(35);
    connect(m_saveBtn, &QPushButton::clicked, this, &SettingsWindow::onSaveClicked);

    m_pushBtn = new QPushButton("Push Manual", this);
    m_pushBtn->setFixedHeight(35);
    connect(m_pushBtn, &QPushButton::clicked, this, &SettingsWindow::pushClicked);

    m_reconnectBtn = new QPushButton("Reconnect", this);
    m_reconnectBtn->setFixedHeight(35);
    connect(m_reconnectBtn, &QPushButton::clicked, this, &SettingsWindow::reconnectClicked);

    m_closeBtn = new QPushButton("Close", this);
    m_closeBtn->setFixedHeight(35);
    connect(m_closeBtn, &QPushButton::clicked, this, &SettingsWindow::hide);

    btnLayout->addWidget(m_saveBtn);
    btnLayout->addWidget(m_pushBtn);
    btnLayout->addWidget(m_reconnectBtn);
    btnLayout->addWidget(m_closeBtn);

    rightLayout->addLayout(btnLayout);
    rightLayout->addStretch();

    // Add layouts to main
    mainLayout->addLayout(leftLayout, 2);

    // Vertical line separator
    QFrame* line = new QFrame(this);
    line->setFrameShape(QFrame::VLine);
    line->setFrameShadow(QFrame::Sunken);
    mainLayout->addWidget(line);

    mainLayout->addLayout(rightLayout, 1);

    setFont(QFont("Segoe UI", 10));
}

void SettingsWindow::onSaveClicked() {
    SettingsData data;
    data.serverUrl = m_serverUrlInput->text();
    data.downloadPath = m_downloadPathInput->text();
    data.pushHotkey = m_hotkeyInput->hotkeyString();
    data.autoCopyImage = m_cbImages->isChecked();
    data.autoCopyFile = m_cbFiles->isChecked();
    data.autoStart = m_cbStartup->isChecked();

    emit saveClicked(data);
}

void SettingsWindow::setServerUrl(const QString& url) {
    m_serverUrlInput->setText(url);
}

void SettingsWindow::setDownloadPath(const QString& path) {
    m_downloadPathInput->setText(path);
}

void SettingsWindow::setHotkey(const QString& hotkey) {
    m_hotkeyInput->setHotkeyString(hotkey);
}

void SettingsWindow::setAutoCopyImage(bool enabled) {
    m_cbImages->setChecked(enabled);
}

void SettingsWindow::setAutoCopyFile(bool enabled) {
    m_cbFiles->setChecked(enabled);
}

void SettingsWindow::setAutoStart(bool enabled) {
    m_cbStartup->setChecked(enabled);
}

void SettingsWindow::setQRContent(const QString& content) {
    m_qrWidget->setContent(content);
}

void SettingsWindow::setStatus(const QString& text, const QString& color) {
    m_statusLabel->setText(QString("Status: %1").arg(text));
    m_statusLabel->setStyleSheet(QString("color: %1;").arg(color));
}

QString SettingsWindow::downloadPath() const {
    return m_downloadPathInput->text();
}

} // namespace ClipboardPush
