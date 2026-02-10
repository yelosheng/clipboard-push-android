#pragma once

#include <QObject>
#include <QString>
#include <QByteArray>
#include <QJsonObject>
#include <functional>
#include <optional>

namespace ClipboardPush {

struct HttpResponse {
    int statusCode = 0;
    QByteArray body;
    QString error;

    bool isSuccess() const { return statusCode >= 200 && statusCode < 300; }
};

struct UploadAuthResponse {
    QString uploadUrl;
    QString downloadUrl;
    bool success = false;
    QString error;
};

class HttpClient : public QObject {
    Q_OBJECT

public:
    explicit HttpClient(QObject* parent = nullptr);
    ~HttpClient() = default;

    void setBaseUrl(const QString& url);
    QString baseUrl() const { return m_baseUrl; }

    // Synchronous API calls (for use in worker threads)
    HttpResponse postJson(const QString& endpoint, const QJsonObject& payload);
    HttpResponse get(const QString& url);
    HttpResponse put(const QString& url, const QByteArray& data, const QString& contentType = "application/octet-stream");

    // Clipboard relay API
    bool relayClipboard(const QString& roomId, const QString& clientId,
                       const QString& encryptedContent, const QString& timestamp);

    // File upload API
    UploadAuthResponse requestUploadAuth(const QString& filename, qint64 size,
                                         const QString& contentType = "application/octet-stream");
    bool uploadFile(const QString& uploadUrl, const QByteArray& data);
    bool relayFileSync(const QString& roomId, const QString& clientId,
                      const QString& downloadUrl, const QString& filename,
                      const QString& fileType, const QString& timestamp);

    // File download
    std::optional<QByteArray> downloadFile(const QString& url);

signals:
    void requestStarted();
    void requestFinished();
    void errorOccurred(const QString& error);

private:
    QString m_baseUrl;
};

} // namespace ClipboardPush
