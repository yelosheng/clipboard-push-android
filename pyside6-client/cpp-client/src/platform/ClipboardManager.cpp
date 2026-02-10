#include "ClipboardManager.h"
#include "core/Logger.h"

#include <QClipboard>
#include <QApplication>
#include <QMimeData>
#include <QUrl>
#include <QImage>
#include <QBuffer>

#ifdef _WIN32
#include <windows.h>
#include <shlobj.h>
#endif

namespace ClipboardPush {

ClipboardManager::ClipboardManager(QObject* parent)
    : QObject(parent)
{
    // Connect to system clipboard changes
    QClipboard* clipboard = QApplication::clipboard();
    connect(clipboard, &QClipboard::dataChanged, this, &ClipboardManager::clipboardChanged);
}

ClipboardContentType ClipboardManager::detectContentType() {
#ifdef _WIN32
    if (!OpenClipboard(nullptr)) {
        return ClipboardContentType::None;
    }

    ClipboardContentType type = ClipboardContentType::None;

    // Check for files first (CF_HDROP)
    if (IsClipboardFormatAvailable(CF_HDROP)) {
        type = ClipboardContentType::Files;
    }
    // Then check for image (CF_DIB or CF_BITMAP)
    else if (IsClipboardFormatAvailable(CF_DIB) || IsClipboardFormatAvailable(CF_BITMAP)) {
        type = ClipboardContentType::Image;
    }
    // Finally check for text
    else if (IsClipboardFormatAvailable(CF_UNICODETEXT) || IsClipboardFormatAvailable(CF_TEXT)) {
        type = ClipboardContentType::Text;
    }

    CloseClipboard();
    return type;
#else
    // Qt-based fallback for non-Windows
    QClipboard* clipboard = QApplication::clipboard();
    const QMimeData* mimeData = clipboard->mimeData();

    if (mimeData->hasUrls()) {
        return ClipboardContentType::Files;
    } else if (mimeData->hasImage()) {
        return ClipboardContentType::Image;
    } else if (mimeData->hasText()) {
        return ClipboardContentType::Text;
    }
    return ClipboardContentType::None;
#endif
}

ClipboardContent ClipboardManager::getContent() {
    ClipboardContent content;
    content.type = detectContentType();

    switch (content.type) {
        case ClipboardContentType::Files:
            content.files = getFilesFromClipboard();
            if (content.files.isEmpty()) {
                content.type = ClipboardContentType::None;
            }
            break;

        case ClipboardContentType::Image:
            content.imageData = getImageFromClipboard();
            content.imageMimeType = "image/png";
            if (content.imageData.isEmpty()) {
                content.type = ClipboardContentType::None;
            }
            break;

        case ClipboardContentType::Text:
            content.text = getTextFromClipboard();
            if (content.text.isEmpty()) {
                content.type = ClipboardContentType::None;
            }
            break;

        default:
            break;
    }

    return content;
}

QStringList ClipboardManager::getFilesFromClipboard() {
    QStringList files;

#ifdef _WIN32
    if (!OpenClipboard(nullptr)) {
        LOG_ERROR("Failed to open clipboard");
        return files;
    }

    HANDLE hDrop = GetClipboardData(CF_HDROP);
    if (hDrop) {
        HDROP hdrop = static_cast<HDROP>(hDrop);
        UINT fileCount = DragQueryFileW(hdrop, 0xFFFFFFFF, nullptr, 0);

        for (UINT i = 0; i < fileCount; ++i) {
            UINT pathLen = DragQueryFileW(hdrop, i, nullptr, 0);
            if (pathLen > 0) {
                std::vector<wchar_t> buffer(pathLen + 1);
                DragQueryFileW(hdrop, i, buffer.data(), pathLen + 1);
                files.append(QString::fromWCharArray(buffer.data()));
            }
        }
    }

    CloseClipboard();
#else
    QClipboard* clipboard = QApplication::clipboard();
    const QMimeData* mimeData = clipboard->mimeData();

    if (mimeData->hasUrls()) {
        for (const QUrl& url : mimeData->urls()) {
            if (url.isLocalFile()) {
                files.append(url.toLocalFile());
            }
        }
    }
#endif

    return files;
}

QByteArray ClipboardManager::getImageFromClipboard() {
    QByteArray imageData;

    QClipboard* clipboard = QApplication::clipboard();
    QImage image = clipboard->image();

    if (!image.isNull()) {
        QBuffer buffer(&imageData);
        buffer.open(QIODevice::WriteOnly);
        image.save(&buffer, "PNG");
    }

    return imageData;
}

QString ClipboardManager::getTextFromClipboard() {
#ifdef _WIN32
    QString text;

    if (!OpenClipboard(nullptr)) {
        return text;
    }

    HANDLE hData = GetClipboardData(CF_UNICODETEXT);
    if (hData) {
        wchar_t* pszText = static_cast<wchar_t*>(GlobalLock(hData));
        if (pszText) {
            text = QString::fromWCharArray(pszText);
            GlobalUnlock(hData);
        }
    }

    CloseClipboard();
    return text;
#else
    return QApplication::clipboard()->text();
#endif
}

bool ClipboardManager::setText(const QString& text) {
#ifdef _WIN32
    if (!OpenClipboard(nullptr)) {
        LOG_ERROR("Failed to open clipboard");
        emit errorOccurred("Failed to open clipboard");
        return false;
    }

    EmptyClipboard();

    // Allocate global memory for the text
    std::wstring wtext = text.toStdWString();
    size_t size = (wtext.length() + 1) * sizeof(wchar_t);

    HGLOBAL hMem = GlobalAlloc(GMEM_MOVEABLE, size);
    if (!hMem) {
        CloseClipboard();
        LOG_ERROR("Failed to allocate memory for clipboard");
        return false;
    }

    wchar_t* pMem = static_cast<wchar_t*>(GlobalLock(hMem));
    if (pMem) {
        memcpy(pMem, wtext.c_str(), size);
        GlobalUnlock(hMem);
    }

    SetClipboardData(CF_UNICODETEXT, hMem);
    CloseClipboard();

    LOG_DEBUG("Text set to clipboard");
    return true;
#else
    QApplication::clipboard()->setText(text);
    return true;
#endif
}

bool ClipboardManager::setFiles(const QStringList& paths) {
#ifdef _WIN32
    if (paths.isEmpty()) {
        return false;
    }

    if (!OpenClipboard(nullptr)) {
        LOG_ERROR("Failed to open clipboard");
        emit errorOccurred("Failed to open clipboard");
        return false;
    }

    EmptyClipboard();

    // Calculate required buffer size
    // DROPFILES structure + null-terminated file paths + final null
    size_t offset = sizeof(DROPFILES);
    size_t totalLen = 0;
    for (const QString& path : paths) {
        totalLen += (path.length() + 1);  // +1 for null terminator
    }
    totalLen += 1;  // Final null terminator

    size_t size = offset + totalLen * sizeof(wchar_t);

    HGLOBAL hMem = GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, size);
    if (!hMem) {
        CloseClipboard();
        LOG_ERROR("Failed to allocate memory for file drop");
        return false;
    }

    char* pMem = static_cast<char*>(GlobalLock(hMem));
    if (!pMem) {
        GlobalFree(hMem);
        CloseClipboard();
        return false;
    }

    // Setup DROPFILES structure
    DROPFILES* df = reinterpret_cast<DROPFILES*>(pMem);
    df->pFiles = static_cast<DWORD>(offset);
    df->pt.x = 0;
    df->pt.y = 0;
    df->fNC = FALSE;
    df->fWide = TRUE;  // Unicode paths

    // Copy file paths
    wchar_t* pPath = reinterpret_cast<wchar_t*>(pMem + offset);
    for (const QString& path : paths) {
        std::wstring wpath = path.toStdWString();
        memcpy(pPath, wpath.c_str(), (wpath.length() + 1) * sizeof(wchar_t));
        pPath += wpath.length() + 1;
    }
    *pPath = L'\0';  // Final null

    GlobalUnlock(hMem);

    if (!SetClipboardData(CF_HDROP, hMem)) {
        GlobalFree(hMem);
        CloseClipboard();
        LOG_ERROR("Failed to set clipboard data");
        return false;
    }

    CloseClipboard();
    LOG_DEBUG("Files set to clipboard: {} files", paths.size());
    return true;
#else
    QClipboard* clipboard = QApplication::clipboard();
    QMimeData* mimeData = new QMimeData();

    QList<QUrl> urls;
    for (const QString& path : paths) {
        urls.append(QUrl::fromLocalFile(path));
    }
    mimeData->setUrls(urls);
    clipboard->setMimeData(mimeData);
    return true;
#endif
}

bool ClipboardManager::setImage(const QByteArray& imageData) {
    QImage image;
    if (!image.loadFromData(imageData)) {
        LOG_ERROR("Failed to load image data");
        emit errorOccurred("Failed to load image data");
        return false;
    }

#ifdef _WIN32
    if (!OpenClipboard(nullptr)) {
        LOG_ERROR("Failed to open clipboard");
        emit errorOccurred("Failed to open clipboard");
        return false;
    }

    EmptyClipboard();

    // Convert to DIB format (BMP without the 14-byte file header)
    QByteArray bmpData;
    QBuffer buffer(&bmpData);
    buffer.open(QIODevice::WriteOnly);
    image.save(&buffer, "BMP");
    buffer.close();

    // Skip the 14-byte BMP file header
    if (bmpData.size() <= 14) {
        CloseClipboard();
        return false;
    }

    QByteArray dibData = bmpData.mid(14);

    HGLOBAL hMem = GlobalAlloc(GMEM_MOVEABLE, dibData.size());
    if (!hMem) {
        CloseClipboard();
        return false;
    }

    void* pMem = GlobalLock(hMem);
    if (pMem) {
        memcpy(pMem, dibData.constData(), dibData.size());
        GlobalUnlock(hMem);
    }

    if (!SetClipboardData(CF_DIB, hMem)) {
        GlobalFree(hMem);
        CloseClipboard();
        return false;
    }

    CloseClipboard();
    LOG_DEBUG("Image set to clipboard");
    return true;
#else
    QApplication::clipboard()->setImage(image);
    return true;
#endif
}

bool ClipboardManager::setImageFromFile(const QString& path) {
    QImage image(path);
    if (image.isNull()) {
        LOG_ERROR("Failed to load image from: {}", path.toStdString());
        emit errorOccurred(QString("Failed to load image: %1").arg(path));
        return false;
    }

    QByteArray imageData;
    QBuffer buffer(&imageData);
    buffer.open(QIODevice::WriteOnly);
    image.save(&buffer, "PNG");

    return setImage(imageData);
}

} // namespace ClipboardPush
