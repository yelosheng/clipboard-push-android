#include "HotkeyManager.h"
#include "core/Logger.h"

#include <QCoreApplication>
#include <QStringList>

#ifdef _WIN32
#include <windows.h>
#endif

namespace ClipboardPush {

HotkeyManager::HotkeyManager(QObject* parent)
    : QObject(parent)
{
    // Install native event filter
    QCoreApplication::instance()->installNativeEventFilter(this);
}

HotkeyManager::~HotkeyManager() {
    unregisterHotkey();
    QCoreApplication::instance()->removeNativeEventFilter(this);
}

bool HotkeyManager::parseHotkeyString(const QString& str, uint& modifiers, uint& vk) {
    modifiers = 0;
    vk = 0;

    QStringList parts = str.split('+', Qt::SkipEmptyParts);
    if (parts.isEmpty()) {
        return false;
    }

    for (const QString& part : parts) {
        QString p = part.trimmed().toLower();

        if (p == "ctrl" || p == "control") {
#ifdef _WIN32
            modifiers |= MOD_CONTROL;
#endif
        }
        else if (p == "alt") {
#ifdef _WIN32
            modifiers |= MOD_ALT;
#endif
        }
        else if (p == "shift") {
#ifdef _WIN32
            modifiers |= MOD_SHIFT;
#endif
        }
        else if (p == "win" || p == "meta" || p == "super") {
#ifdef _WIN32
            modifiers |= MOD_WIN;
#endif
        }
        else if (p.startsWith('f') && p.length() > 1) {
            // Function keys F1-F24
            bool ok;
            int fNum = p.mid(1).toInt(&ok);
            if (ok && fNum >= 1 && fNum <= 24) {
#ifdef _WIN32
                vk = VK_F1 + fNum - 1;
#endif
            }
        }
        else if (p.length() == 1) {
            // Single character (A-Z, 0-9)
            QChar c = p.at(0).toUpper();
            if (c >= 'A' && c <= 'Z') {
#ifdef _WIN32
                vk = c.unicode();
#endif
            }
            else if (c >= '0' && c <= '9') {
#ifdef _WIN32
                vk = c.unicode();
#endif
            }
        }
        else if (p == "space") {
#ifdef _WIN32
            vk = VK_SPACE;
#endif
        }
        else if (p == "tab") {
#ifdef _WIN32
            vk = VK_TAB;
#endif
        }
        else if (p == "enter" || p == "return") {
#ifdef _WIN32
            vk = VK_RETURN;
#endif
        }
        else if (p == "escape" || p == "esc") {
#ifdef _WIN32
            vk = VK_ESCAPE;
#endif
        }
        else if (p == "backspace") {
#ifdef _WIN32
            vk = VK_BACK;
#endif
        }
        else if (p == "delete" || p == "del") {
#ifdef _WIN32
            vk = VK_DELETE;
#endif
        }
        else if (p == "insert" || p == "ins") {
#ifdef _WIN32
            vk = VK_INSERT;
#endif
        }
        else if (p == "home") {
#ifdef _WIN32
            vk = VK_HOME;
#endif
        }
        else if (p == "end") {
#ifdef _WIN32
            vk = VK_END;
#endif
        }
        else if (p == "pageup" || p == "pgup") {
#ifdef _WIN32
            vk = VK_PRIOR;
#endif
        }
        else if (p == "pagedown" || p == "pgdn") {
#ifdef _WIN32
            vk = VK_NEXT;
#endif
        }
    }

    return vk != 0;  // Need at least a main key
}

bool HotkeyManager::registerHotkey(const QString& hotkeyStr) {
    // Unregister existing hotkey first
    unregisterHotkey();

    if (hotkeyStr.isEmpty()) {
        return false;
    }

#ifdef _WIN32
    uint modifiers, vk;
    if (!parseHotkeyString(hotkeyStr, modifiers, vk)) {
        LOG_ERROR("Failed to parse hotkey: {}", hotkeyStr.toStdString());
        emit registrationFailed(QString("Invalid hotkey format: %1").arg(hotkeyStr));
        return false;
    }

    m_modifiers = modifiers;
    m_vk = vk;

    // Add MOD_NOREPEAT to prevent repeated triggers when key is held
    UINT finalModifiers = m_modifiers | MOD_NOREPEAT;

    if (!RegisterHotKey(nullptr, m_hotkeyId, finalModifiers, m_vk)) {
        DWORD error = GetLastError();
        LOG_ERROR("Failed to register hotkey {}: error {}", hotkeyStr.toStdString(), error);

        QString errorMsg;
        if (error == ERROR_HOTKEY_ALREADY_REGISTERED) {
            errorMsg = QString("Hotkey %1 is already in use by another application").arg(hotkeyStr);
        } else {
            errorMsg = QString("Failed to register hotkey: error %1").arg(error);
        }

        emit registrationFailed(errorMsg);
        return false;
    }

    m_hotkeyStr = hotkeyStr;
    m_registered = true;
    LOG_INFO("Hotkey registered: {}", hotkeyStr.toStdString());
    return true;
#else
    LOG_WARN("Hotkey registration not implemented for this platform");
    return false;
#endif
}

void HotkeyManager::unregisterHotkey() {
#ifdef _WIN32
    if (m_registered) {
        UnregisterHotKey(nullptr, m_hotkeyId);
        m_registered = false;
        LOG_DEBUG("Hotkey unregistered");
    }
#endif
    m_hotkeyStr.clear();
}

#if QT_VERSION >= QT_VERSION_CHECK(6, 0, 0)
bool HotkeyManager::nativeEventFilter(const QByteArray& eventType, void* message, qintptr* result)
#else
bool HotkeyManager::nativeEventFilter(const QByteArray& eventType, void* message, long* result)
#endif
{
    Q_UNUSED(result);

#ifdef _WIN32
    if (eventType == "windows_generic_MSG" || eventType == "windows_dispatcher_MSG") {
        MSG* msg = static_cast<MSG*>(message);

        if (msg->message == WM_HOTKEY && msg->wParam == static_cast<WPARAM>(m_hotkeyId)) {
            LOG_DEBUG("Hotkey triggered");
            emit hotkeyTriggered();
            return true;  // Event handled
        }
    }
#else
    Q_UNUSED(eventType);
    Q_UNUSED(message);
#endif

    return false;  // Let Qt handle other events
}

} // namespace ClipboardPush
