#include "HotkeyRecorderEdit.h"
#include <QStringList>

namespace ClipboardPush {

HotkeyRecorderEdit::HotkeyRecorderEdit(QWidget* parent)
    : QLineEdit(parent)
{
    setPlaceholderText("Press keys to set hotkey...");
    setReadOnly(true);
    setStyleSheet("background-color: #f8f9fa; border: 1px solid #ced4da; padding: 5px;");
}

QString HotkeyRecorderEdit::hotkeyString() const {
    return text();
}

void HotkeyRecorderEdit::setHotkeyString(const QString& hotkey) {
    setText(hotkey);
}

void HotkeyRecorderEdit::focusInEvent(QFocusEvent* event) {
    m_recording = true;
    m_pressedKeys.clear();
    clear();
    setStyleSheet("background-color: #fff3cd; border: 1px solid #ffeeba; padding: 5px;");
    QLineEdit::focusInEvent(event);
}

void HotkeyRecorderEdit::focusOutEvent(QFocusEvent* event) {
    m_recording = false;
    setStyleSheet("background-color: #f8f9fa; border: 1px solid #ced4da; padding: 5px;");

    if (text().isEmpty() || text().endsWith("...")) {
        setText("None");
    }

    QLineEdit::focusOutEvent(event);
}

void HotkeyRecorderEdit::keyPressEvent(QKeyEvent* event) {
    if (!m_recording) {
        QLineEdit::keyPressEvent(event);
        return;
    }

    int key = event->key();

    // Clear on Backspace/Delete
    if (key == Qt::Key_Backspace || key == Qt::Key_Delete) {
        clear();
        return;
    }

    // Cancel on Escape
    if (key == Qt::Key_Escape) {
        clear();
        clearFocus();
        return;
    }

    // Just modifier pressed - show partial state
    if (key == Qt::Key_Control || key == Qt::Key_Alt ||
        key == Qt::Key_Shift || key == Qt::Key_Meta) {
        QStringList parts;
        Qt::KeyboardModifiers mods = event->modifiers();

        if (mods & Qt::ControlModifier) parts.append("Ctrl");
        if (mods & Qt::AltModifier) parts.append("Alt");
        if (mods & Qt::ShiftModifier) parts.append("Shift");
        if (mods & Qt::MetaModifier) parts.append("Win");

        if (!parts.isEmpty()) {
            setText(parts.join("+") + "+...");
        }
        return;
    }

    // Build hotkey string
    QString hotkey = buildHotkeyString(event->modifiers(), key);
    if (!hotkey.isEmpty()) {
        setText(hotkey);
        emit hotkeyRecorded(hotkey);
        clearFocus();  // Auto-finish recording
    }
}

void HotkeyRecorderEdit::keyReleaseEvent(QKeyEvent* event) {
    // Just forward to base class
    QLineEdit::keyReleaseEvent(event);
}

QString HotkeyRecorderEdit::buildHotkeyString(Qt::KeyboardModifiers modifiers, int key) {
    QStringList parts;

    if (modifiers & Qt::ControlModifier) parts.append("Ctrl");
    if (modifiers & Qt::AltModifier) parts.append("Alt");
    if (modifiers & Qt::ShiftModifier) parts.append("Shift");
    if (modifiers & Qt::MetaModifier) parts.append("Win");

    QString mainKey;

    // Function keys
    if (key >= Qt::Key_F1 && key <= Qt::Key_F12) {
        mainKey = QString("F%1").arg(key - Qt::Key_F1 + 1);
    }
    // Letters
    else if (key >= Qt::Key_A && key <= Qt::Key_Z) {
        mainKey = QChar(key);
    }
    // Numbers
    else if (key >= Qt::Key_0 && key <= Qt::Key_9) {
        mainKey = QChar(key);
    }
    // Special keys
    else if (key == Qt::Key_Space) {
        mainKey = "Space";
    }
    else if (key == Qt::Key_Tab) {
        mainKey = "Tab";
    }
    else if (key == Qt::Key_Return || key == Qt::Key_Enter) {
        mainKey = "Enter";
    }
    else if (key == Qt::Key_Insert) {
        mainKey = "Insert";
    }
    else if (key == Qt::Key_Delete) {
        mainKey = "Delete";
    }
    else if (key == Qt::Key_Home) {
        mainKey = "Home";
    }
    else if (key == Qt::Key_End) {
        mainKey = "End";
    }
    else if (key == Qt::Key_PageUp) {
        mainKey = "PageUp";
    }
    else if (key == Qt::Key_PageDown) {
        mainKey = "PageDown";
    }

    if (mainKey.isEmpty()) {
        return QString();  // Invalid key
    }

    parts.append(mainKey);
    return parts.join("+");
}

} // namespace ClipboardPush
