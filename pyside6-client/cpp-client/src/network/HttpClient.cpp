#include "HttpClient.h"
#include "core/Logger.h"

#include <cpr/cpr.h>
#include <QJsonDocument>

namespace ClipboardPush {

HttpClient::HttpClient(QObject* parent)
    : QObject(parent)
{
}

void HttpClient::setBaseUrl(const QString& url) {
    m_baseUrl = url;
    // Remove trailing slash
    while (m_baseUrl.endsWith('/')) {
        m_baseUrl.chop(1);
    }
}

HttpResponse HttpClient::postJson(const QString& endpoint, const QJsonObject& payload) {
    HttpResponse response;

    QString url = endpoint.startsWith("http") ? endpoint : m_baseUrl + endpoint;

    QJsonDocument doc(payload);
    std::string body = doc.toJson(QJsonDocument::Compact).toStdString();

    emit requestStarted();

    try {
        cpr::Response r = cpr::Post(
            cpr::Url{url.toStdString()},
            cpr::Header{{"Content-Type", "application/json"}},
            cpr::Body{body},
            cpr::Timeout{5000},
            cpr::VerifySsl{false}
        );

        response.statusCode = static_cast<int>(r.status_code);
        response.body = QByteArray::fromStdString(r.text);

        if (r.error) {
            response.error = QString::fromStdString(r.error.message);
            LOG_ERROR("HTTP POST error: {}", r.error.message);
        }
    } catch (const std::exception& e) {
        response.error = QString::fromUtf8(e.what());
        LOG_ERROR("HTTP POST exception: {}", e.what());
    }

    emit requestFinished();
    return response;
}

HttpResponse HttpClient::get(const QString& url) {
    HttpResponse response;

    emit requestStarted();

    try {
        cpr::Response r = cpr::Get(
            cpr::Url{url.toStdString()},
            cpr::Timeout{60000},
            cpr::VerifySsl{false}
        );

        response.statusCode = static_cast<int>(r.status_code);
        response.body = QByteArray::fromStdString(r.text);

        if (r.error) {
            response.error = QString::fromStdString(r.error.message);
            LOG_ERROR("HTTP GET error: {}", r.error.message);
        }
    } catch (const std::exception& e) {
        response.error = QString::fromUtf8(e.what());
        LOG_ERROR("HTTP GET exception: {}", e.what());
    }

    emit requestFinished();
    return response;
}

HttpResponse HttpClient::put(const QString& url, const QByteArray& data, const QString& contentType) {
    HttpResponse response;

    emit requestStarted();

    try {
        cpr::Response r = cpr::Put(
            cpr::Url{url.toStdString()},
            cpr::Header{{"Content-Type", contentType.toStdString()}},
            cpr::Body{data.toStdString()},
            cpr::Timeout{300000},  // 5 minutes for file uploads
            cpr::VerifySsl{false}
        );

        response.statusCode = static_cast<int>(r.status_code);
        response.body = QByteArray::fromStdString(r.text);

        if (r.error) {
            response.error = QString::fromStdString(r.error.message);
            LOG_ERROR("HTTP PUT error: {}", r.error.message);
        }
    } catch (const std::exception& e) {
        response.error = QString::fromUtf8(e.what());
        LOG_ERROR("HTTP PUT exception: {}", e.what());
    }

    emit requestFinished();
    return response;
}

bool HttpClient::relayClipboard(const QString& roomId, const QString& clientId,
                                const QString& encryptedContent, const QString& timestamp) {
    QJsonObject data;
    data["content"] = encryptedContent;
    data["encrypted"] = true;
    data["timestamp"] = timestamp;
    data["source"] = clientId;

    QJsonObject payload;
    payload["room"] = roomId;
    payload["event"] = "clipboard_sync";
    payload["data"] = data;
    payload["client_id"] = clientId;

    HttpResponse response = postJson("/api/relay", payload);

    if (response.isSuccess()) {
        LOG_INFO("Clipboard relayed successfully");
        return true;
    } else {
        LOG_ERROR("Clipboard relay failed: {} - {}", response.statusCode, response.error.toStdString());
        emit errorOccurred(response.error);
        return false;
    }
}

UploadAuthResponse HttpClient::requestUploadAuth(const QString& filename, qint64 size,
                                                  const QString& contentType) {
    UploadAuthResponse auth;

    QJsonObject payload;
    payload["filename"] = filename;
    payload["size"] = size;
    payload["content_type"] = contentType;

    HttpResponse response = postJson("/api/file/upload_auth", payload);

    if (response.isSuccess()) {
        QJsonDocument doc = QJsonDocument::fromJson(response.body);
        QJsonObject obj = doc.object();

        auth.uploadUrl = obj["upload_url"].toString();
        auth.downloadUrl = obj["download_url"].toString();
        auth.success = !auth.uploadUrl.isEmpty();

        if (!auth.success) {
            auth.error = "Missing upload_url in response";
        }
    } else {
        auth.error = response.error.isEmpty()
            ? QString("HTTP %1").arg(response.statusCode)
            : response.error;
    }

    return auth;
}

bool HttpClient::uploadFile(const QString& uploadUrl, const QByteArray& data) {
    HttpResponse response = put(uploadUrl, data);
    return response.isSuccess();
}

bool HttpClient::relayFileSync(const QString& roomId, const QString& clientId,
                               const QString& downloadUrl, const QString& filename,
                               const QString& fileType, const QString& timestamp) {
    QJsonObject data;
    data["download_url"] = downloadUrl;
    data["filename"] = filename;
    data["type"] = fileType;
    data["timestamp"] = timestamp;

    QJsonObject payload;
    payload["room"] = roomId;
    payload["event"] = "file_sync";
    payload["data"] = data;
    payload["client_id"] = clientId;

    HttpResponse response = postJson("/api/relay", payload);

    if (response.isSuccess()) {
        LOG_INFO("File sync relayed: {}", filename.toStdString());
        return true;
    } else {
        LOG_ERROR("File sync relay failed: {} - {}", response.statusCode, response.error.toStdString());
        emit errorOccurred(response.error);
        return false;
    }
}

std::optional<QByteArray> HttpClient::downloadFile(const QString& url) {
    HttpResponse response = get(url);

    if (response.isSuccess()) {
        return response.body;
    } else {
        emit errorOccurred(QString("Download failed: %1").arg(response.error));
        return std::nullopt;
    }
}

} // namespace ClipboardPush
