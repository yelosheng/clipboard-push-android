#pragma once

#include <QLabel>
#include <QString>
#include <QImage>

namespace ClipboardPush {

class QRCodeWidget : public QLabel {
    Q_OBJECT

public:
    explicit QRCodeWidget(QWidget* parent = nullptr);
    ~QRCodeWidget() = default;

    void setContent(const QString& content);
    void clear();

    QString content() const { return m_content; }

private:
    QImage generateQRCode(const QString& text);

    QString m_content;
    int m_size = 200;
    int m_border = 1;
};

} // namespace ClipboardPush
