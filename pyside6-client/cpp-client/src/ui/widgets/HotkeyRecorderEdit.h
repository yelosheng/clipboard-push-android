#pragma once

#include <QLineEdit>
#include <QKeyEvent>
#include <QSet>

namespace ClipboardPush {

class HotkeyRecorderEdit : public QLineEdit {
    Q_OBJECT

public:
    explicit HotkeyRecorderEdit(QWidget* parent = nullptr);
    ~HotkeyRecorderEdit() = default;

    QString hotkeyString() const;
    void setHotkeyString(const QString& hotkey);

signals:
    void hotkeyRecorded(const QString& hotkey);

protected:
    void focusInEvent(QFocusEvent* event) override;
    void focusOutEvent(QFocusEvent* event) override;
    void keyPressEvent(QKeyEvent* event) override;
    void keyReleaseEvent(QKeyEvent* event) override;

private:
    void updateDisplay();
    QString buildHotkeyString(Qt::KeyboardModifiers modifiers, int key);

    QSet<int> m_pressedKeys;
    bool m_recording = false;
};

} // namespace ClipboardPush
