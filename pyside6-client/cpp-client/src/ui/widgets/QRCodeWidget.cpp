#include "QRCodeWidget.h"
#include "core/Logger.h"

#include <QPixmap>
#include <QPainter>

// nayuki-qr-code-generator
#include <qrcodegen.hpp>

namespace ClipboardPush {

QRCodeWidget::QRCodeWidget(QWidget* parent)
    : QLabel(parent)
{
    setAlignment(Qt::AlignCenter);
    setFixedSize(m_size, m_size);
    setStyleSheet("border: 1px solid #ccc; background: white;");
}

void QRCodeWidget::setContent(const QString& content) {
    if (content.isEmpty()) {
        clear();
        return;
    }

    m_content = content;

    QImage qrImage = generateQRCode(content);
    if (!qrImage.isNull()) {
        QPixmap pixmap = QPixmap::fromImage(qrImage);
        setPixmap(pixmap.scaled(size(), Qt::KeepAspectRatio, Qt::SmoothTransformation));
    }
}

void QRCodeWidget::clear() {
    m_content.clear();
    QLabel::clear();
}

QImage QRCodeWidget::generateQRCode(const QString& text) {
    try {
        // Generate QR code using nayuki library
        qrcodegen::QrCode qr = qrcodegen::QrCode::encodeText(
            text.toUtf8().constData(),
            qrcodegen::QrCode::Ecc::MEDIUM
        );

        int size = qr.getSize();
        int scale = m_size / (size + m_border * 2);
        if (scale < 1) scale = 1;

        int imageSize = (size + m_border * 2) * scale;
        QImage image(imageSize, imageSize, QImage::Format_RGB32);
        image.fill(Qt::white);

        QPainter painter(&image);
        painter.setPen(Qt::NoPen);
        painter.setBrush(Qt::black);

        for (int y = 0; y < size; ++y) {
            for (int x = 0; x < size; ++x) {
                if (qr.getModule(x, y)) {
                    painter.drawRect(
                        (x + m_border) * scale,
                        (y + m_border) * scale,
                        scale,
                        scale
                    );
                }
            }
        }

        return image;

    } catch (const std::exception& e) {
        LOG_ERROR("Failed to generate QR code: {}", e.what());
        return QImage();
    }
}

} // namespace ClipboardPush
