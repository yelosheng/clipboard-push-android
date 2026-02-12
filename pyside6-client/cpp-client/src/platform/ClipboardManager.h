#pragma once

#include <QObject>
#include <QString>
#include <QStringList>
#include <QByteArray>
#include <optional>

namespace ClipboardPush {

enum class ClipboardContentType {
    None,
    Text,
    Files,
    Image
};

struct ClipboardContent {
    ClipboardContentType type = ClipboardContentType::None;
    QString text;
    QStringList files;
    QByteArray imageData;  // PNG format
    QString imageMimeType;
};

class ClipboardManager : public QObject {
    Q_OBJECT

public:
    explicit ClipboardManager(QObject* parent = nullptr);
    ~ClipboardManager() = default;

    // Read from clipboard
    ClipboardContent getContent();
    ClipboardContentType detectContentType();

    // Write to clipboard
    bool setText(const QString& text);
    bool setFiles(const QStringList& paths);
    bool setImage(const QByteArray& imageData);
    bool setImageFromFile(const QString& path);

signals:
    void clipboardChanged();
    void errorOccurred(const QString& error);

private:
    // Win32 helpers
    QStringList getFilesFromClipboard();
    QByteArray getImageFromClipboard();
    QString getTextFromClipboard();
};

} // namespace ClipboardPush
