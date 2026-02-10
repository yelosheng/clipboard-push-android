#pragma once

#include <QObject>
#include <QString>
#include <QAbstractNativeEventFilter>

#ifdef _WIN32
#include <windows.h>
#endif

namespace ClipboardPush {

class HotkeyManager : public QObject, public QAbstractNativeEventFilter {
    Q_OBJECT

public:
    explicit HotkeyManager(QObject* parent = nullptr);
    ~HotkeyManager();

    bool registerHotkey(const QString& hotkeyStr);
    void unregisterHotkey();

    QString currentHotkey() const { return m_hotkeyStr; }
    bool isRegistered() const { return m_registered; }

    // Parse hotkey string like "Ctrl+F6" to modifiers and virtual key
    static bool parseHotkeyString(const QString& str, uint& modifiers, uint& vk);

    // QAbstractNativeEventFilter
#if QT_VERSION >= QT_VERSION_CHECK(6, 0, 0)
    bool nativeEventFilter(const QByteArray& eventType, void* message, qintptr* result) override;
#else
    bool nativeEventFilter(const QByteArray& eventType, void* message, long* result) override;
#endif

signals:
    void hotkeyTriggered();
    void registrationFailed(const QString& error);

private:
    QString m_hotkeyStr;
    bool m_registered = false;
    int m_hotkeyId = 1;  // Unique ID for RegisterHotKey

#ifdef _WIN32
    UINT m_modifiers = 0;
    UINT m_vk = 0;
#endif
};

} // namespace ClipboardPush
