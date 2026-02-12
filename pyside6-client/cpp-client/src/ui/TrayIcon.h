#pragma once

#include <QObject>
#include <QSystemTrayIcon>
#include <QMenu>

namespace ClipboardPush {

class TrayIcon : public QObject {
    Q_OBJECT

public:
    explicit TrayIcon(QObject* parent = nullptr);
    ~TrayIcon();

    void setIcon(const QIcon& icon);
    void show();
    void hide();

    void showMessage(const QString& title, const QString& message,
                     QSystemTrayIcon::MessageIcon icon = QSystemTrayIcon::Information,
                     int msecs = 3000);

signals:
    void pushClicked();
    void showMainClicked();
    void settingsClicked();
    void quitClicked();
    void activated();
    void messageClicked();

private slots:
    void onActivated(QSystemTrayIcon::ActivationReason reason);

private:
    void setupMenu();

    QSystemTrayIcon* m_trayIcon = nullptr;
    QMenu* m_menu = nullptr;
};

} // namespace ClipboardPush
