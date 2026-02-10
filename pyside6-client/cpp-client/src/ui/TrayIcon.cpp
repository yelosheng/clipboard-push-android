#include "TrayIcon.h"

#include <QAction>

namespace ClipboardPush {

TrayIcon::TrayIcon(QObject* parent)
    : QObject(parent)
    , m_trayIcon(new QSystemTrayIcon(this))
    , m_menu(new QMenu())
{
    setupMenu();
    m_trayIcon->setContextMenu(m_menu);

    connect(m_trayIcon, &QSystemTrayIcon::activated,
            this, &TrayIcon::onActivated);
}

TrayIcon::~TrayIcon() {
    delete m_menu;
}

void TrayIcon::setupMenu() {
    QAction* pushAction = new QAction("Push Clipboard", m_menu);
    connect(pushAction, &QAction::triggered, this, &TrayIcon::pushClicked);

    m_menu->addAction(pushAction);
    m_menu->addSeparator();

    QAction* showMainAction = new QAction("Open Main Window", m_menu);
    connect(showMainAction, &QAction::triggered, this, &TrayIcon::showMainClicked);
    m_menu->addAction(showMainAction);

    QAction* settingsAction = new QAction("Settings", m_menu);
    connect(settingsAction, &QAction::triggered, this, &TrayIcon::settingsClicked);
    m_menu->addAction(settingsAction);

    QAction* quitAction = new QAction("Quit", m_menu);
    connect(quitAction, &QAction::triggered, this, &TrayIcon::quitClicked);
    m_menu->addAction(quitAction);
}

void TrayIcon::setIcon(const QIcon& icon) {
    m_trayIcon->setIcon(icon);
}

void TrayIcon::show() {
    m_trayIcon->show();
}

void TrayIcon::hide() {
    m_trayIcon->hide();
}

void TrayIcon::showMessage(const QString& title, const QString& message,
                           QSystemTrayIcon::MessageIcon icon, int msecs) {
    m_trayIcon->showMessage(title, message, icon, msecs);
}

void TrayIcon::onActivated(QSystemTrayIcon::ActivationReason reason) {
    if (reason == QSystemTrayIcon::Trigger) {
        emit activated();
    }
}

} // namespace ClipboardPush
