#include "Network.h"
#include "Utils.h"
#include "Logger.h"
#include <windows.h>
#include <winhttp.h>
#include <thread>
#include <atomic>

namespace ClipboardPush {
namespace Network {

class WinHttpHandle {
    HINTERNET h = NULL;
public:
    WinHttpHandle(HINTERNET handle) : h(handle) {}
    ~WinHttpHandle() { if (h) WinHttpCloseHandle(h); }
    operator HINTERNET() const { return h; }
    HINTERNET* operator&() { return &h; }
    bool isValid() const { return h != NULL; }
};

struct UrlComponents {
    std::wstring host;
    int port;
    std::wstring path;
    bool secure;
};

UrlComponents ParseUrl(const std::string& url) {
    std::string normalizedUrl = url;
    bool isWss = (url.substr(0, 6) == "wss://");
    bool isWs = (url.substr(0, 5) == "ws://");
    
    if (isWss) normalizedUrl.replace(0, 3, "http"); // wss:// -> https://
    else if (isWs) normalizedUrl.replace(0, 2, "http"); // ws:// -> http://

    std::wstring wurl = Utils::ToWide(normalizedUrl);
    URL_COMPONENTS urlComp;
    ZeroMemory(&urlComp, sizeof(urlComp));
    urlComp.dwStructSize = sizeof(urlComp);
    urlComp.dwHostNameLength = (DWORD)-1;
    urlComp.dwUrlPathLength = (DWORD)-1;
    urlComp.dwSchemeLength = (DWORD)-1;

    if (!WinHttpCrackUrl(wurl.c_str(), (DWORD)wurl.length(), 0, &urlComp)) {
        LOG_ERROR("WinHttpCrackUrl failed for %s", url.c_str());
        return {};
    }

    UrlComponents res;
    res.host = std::wstring(urlComp.lpszHostName, urlComp.dwHostNameLength);
    res.port = urlComp.nPort;
    
    // Combine path and query string (ExtraInfo contains ?params)
    res.path = std::wstring(urlComp.lpszUrlPath, urlComp.dwUrlPathLength);
    if (urlComp.dwExtraInfoLength > 0) {
        res.path += std::wstring(urlComp.lpszExtraInfo, urlComp.dwExtraInfoLength);
    }

    res.secure = (urlComp.nScheme == INTERNET_SCHEME_HTTPS || isWss);
    return res;
}

HttpResponse HttpClient::Post(const std::string& url, const std::string& body, const std::string& contentType) {
    auto comp = ParseUrl(url);
    if (comp.host.empty()) return {0, ""};

    WinHttpHandle hSession = WinHttpOpen(L"ClipboardPush/3.0", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY, WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!hSession.isValid()) return {0, ""};

    WinHttpHandle hConnect = WinHttpConnect(hSession, comp.host.c_str(), comp.port, 0);
    if (!hConnect.isValid()) return {0, ""};

    DWORD flags = comp.secure ? WINHTTP_FLAG_SECURE : 0;
    WinHttpHandle hRequest = WinHttpOpenRequest(hConnect, L"POST", comp.path.c_str(), NULL, WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, flags);
    if (!hRequest.isValid()) return {0, ""};

    std::wstring headers = L"Content-Type: " + Utils::ToWide(contentType);
    
    if (!WinHttpSendRequest(hRequest, headers.c_str(), (DWORD)headers.length(), (LPVOID)body.c_str(), (DWORD)body.length(), (DWORD)body.length(), 0)) {
        return {0, ""};
    }

    if (!WinHttpReceiveResponse(hRequest, NULL)) return {0, ""};

    DWORD statusCode = 0;
    DWORD size = sizeof(statusCode);
    WinHttpQueryHeaders(hRequest, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER, WINHTTP_HEADER_NAME_BY_INDEX, &statusCode, &size, WINHTTP_NO_HEADER_INDEX);

    std::string responseBody;
    DWORD dwSize = 0;
    do {
        dwSize = 0;
        if (!WinHttpQueryDataAvailable(hRequest, &dwSize)) break;
        if (dwSize == 0) break;
        
        std::vector<char> buffer(dwSize);
        DWORD dwDownloaded = 0;
        if (WinHttpReadData(hRequest, buffer.data(), dwSize, &dwDownloaded)) {
            responseBody.append(buffer.data(), dwDownloaded);
        }
    } while (dwSize > 0);

    return {(int)statusCode, responseBody};
}

// ... Put and Get are very similar, omitting for brevity in this step to save tokens, focusing on WebSocket

struct WebSocketClient::Impl {
    HINTERNET hSession = NULL;
    HINTERNET hConnect = NULL;
    HINTERNET hRequest = NULL;
    HINTERNET hWebSocket = NULL;
    
    OnOpenCallback onOpen;
    OnMessageCallback onMessage;
    OnCloseCallback onClose;
    OnErrorCallback onError;
    
    std::thread receiveThread;
    std::atomic<bool> running{false};

    ~Impl() {
        running = false;
        if (hWebSocket) WinHttpWebSocketClose(hWebSocket, WINHTTP_WEB_SOCKET_SUCCESS_CLOSE_STATUS, NULL, 0);
        if (receiveThread.joinable()) receiveThread.join();
        if (hWebSocket) WinHttpCloseHandle(hWebSocket);
        if (hRequest) WinHttpCloseHandle(hRequest);
        if (hConnect) WinHttpCloseHandle(hConnect);
        if (hSession) WinHttpCloseHandle(hSession);
    }
};

WebSocketClient::WebSocketClient() : m_impl(std::make_unique<Impl>()) {}
WebSocketClient::~WebSocketClient() = default;

void WebSocketClient::SetCallbacks(OnOpenCallback onOpen, OnMessageCallback onMessage, OnCloseCallback onClose, OnErrorCallback onError) {
    m_impl->onOpen = onOpen;
    m_impl->onMessage = onMessage;
    m_impl->onClose = onClose;
    m_impl->onError = onError;
}

void WebSocketClient::Connect(const std::string& url) {
    if (m_impl->running) Close();

    auto comp = ParseUrl(url);
    if (comp.host.empty()) {
        if (m_impl->onError) m_impl->onError("Invalid URL");
        return;
    }

    m_impl->hSession = WinHttpOpen(L"ClipboardPush/3.0", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY, WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!m_impl->hSession) return;

    m_impl->hConnect = WinHttpConnect(m_impl->hSession, comp.host.c_str(), comp.port, 0);
    if (!m_impl->hConnect) return;

    m_impl->hRequest = WinHttpOpenRequest(m_impl->hConnect, L"GET", comp.path.c_str(), NULL, WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, comp.secure ? WINHTTP_FLAG_SECURE : 0);
    if (!m_impl->hRequest) return;

    if (!WinHttpSetOption(m_impl->hRequest, WINHTTP_OPTION_UPGRADE_TO_WEB_SOCKET, NULL, 0)) return;

    if (!WinHttpSendRequest(m_impl->hRequest, WINHTTP_NO_ADDITIONAL_HEADERS, 0, NULL, 0, 0, 0)) return;
    if (!WinHttpReceiveResponse(m_impl->hRequest, NULL)) return;

    m_impl->hWebSocket = WinHttpWebSocketCompleteUpgrade(m_impl->hRequest, NULL);
    if (!m_impl->hWebSocket) return;

    m_impl->running = true;
    if (m_impl->onOpen) m_impl->onOpen();

    m_impl->receiveThread = std::thread([this]() {
        BYTE buffer[4096];
        DWORD bytesRead = 0;
        WINHTTP_WEB_SOCKET_BUFFER_TYPE bufferType;
        
        while (m_impl->running) {
            DWORD error = WinHttpWebSocketReceive(m_impl->hWebSocket, buffer, sizeof(buffer), &bytesRead, &bufferType);
            if (error != ERROR_SUCCESS) {
                m_impl->running = false;
                if (m_impl->onError) m_impl->onError("WebSocket Receive Error");
                break;
            }
            
            if (bufferType == WINHTTP_WEB_SOCKET_CLOSE_BUFFER_TYPE) {
                m_impl->running = false;
                if (m_impl->onClose) m_impl->onClose();
                break;
            }
            
            if (bufferType == WINHTTP_WEB_SOCKET_UTF8_MESSAGE_BUFFER_TYPE || bufferType == WINHTTP_WEB_SOCKET_UTF8_FRAGMENT_BUFFER_TYPE) {
                if (m_impl->onMessage) {
                    std::string msg((char*)buffer, bytesRead);
                    m_impl->onMessage(msg);
                }
            }
        }
    });
}

void WebSocketClient::Send(const std::string& message) {
    if (m_impl->hWebSocket && m_impl->running) {
        WinHttpWebSocketSend(m_impl->hWebSocket, WINHTTP_WEB_SOCKET_UTF8_MESSAGE_BUFFER_TYPE, (PVOID)message.c_str(), (DWORD)message.length());
    }
}

void WebSocketClient::Close() {
    m_impl->running = false;
    if (m_impl->hWebSocket) WinHttpWebSocketShutdown(m_impl->hWebSocket, WINHTTP_WEB_SOCKET_SUCCESS_CLOSE_STATUS, NULL, 0);
}

std::optional<std::vector<uint8_t>> HttpClient::Get(const std::string& url) {
    auto comp = ParseUrl(url);
    if (comp.host.empty()) return std::nullopt;

    WinHttpHandle hSession = WinHttpOpen(L"ClipboardPush/3.0", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY, WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!hSession.isValid()) return std::nullopt;

    WinHttpHandle hConnect = WinHttpConnect(hSession, comp.host.c_str(), comp.port, 0);
    if (!hConnect.isValid()) return std::nullopt;

    DWORD flags = comp.secure ? WINHTTP_FLAG_SECURE : 0;
    WinHttpHandle hRequest = WinHttpOpenRequest(hConnect, L"GET", comp.path.c_str(), NULL, WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, flags);
    if (!hRequest.isValid()) return std::nullopt;

    if (!WinHttpSendRequest(hRequest, WINHTTP_NO_ADDITIONAL_HEADERS, 0, WINHTTP_NO_REQUEST_DATA, 0, 0, 0)) {
        return std::nullopt;
    }

    if (!WinHttpReceiveResponse(hRequest, NULL)) return std::nullopt;

    std::vector<uint8_t> responseData;
    DWORD dwSize = 0;
    do {
        dwSize = 0;
        if (!WinHttpQueryDataAvailable(hRequest, &dwSize)) break;
        if (dwSize == 0) break;
        
        std::vector<uint8_t> buffer(dwSize);
        DWORD dwDownloaded = 0;
        if (WinHttpReadData(hRequest, buffer.data(), dwSize, &dwDownloaded)) {
            responseData.insert(responseData.end(), buffer.begin(), buffer.begin() + dwDownloaded);
        }
    } while (dwSize > 0);

    return responseData;
}

HttpResponse HttpClient::Put(const std::string& url, const std::vector<uint8_t>& data) {
    auto comp = ParseUrl(url);
    if (comp.host.empty()) return {0, ""};

    WinHttpHandle hSession = WinHttpOpen(L"ClipboardPush/3.0", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY, WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!hSession.isValid()) return {0, ""};

    WinHttpHandle hConnect = WinHttpConnect(hSession, comp.host.c_str(), comp.port, 0);
    if (!hConnect.isValid()) return {0, ""};

    DWORD flags = comp.secure ? WINHTTP_FLAG_SECURE : 0;
    // Use NULL for accept types to avoid sending "Accept: */*" which can break some signatures
    WinHttpHandle hRequest = WinHttpOpenRequest(hConnect, L"PUT", comp.path.c_str(), NULL, WINHTTP_NO_REFERER, NULL, flags);
    if (!hRequest.isValid()) return {0, ""};

    if (!WinHttpSendRequest(hRequest, WINHTTP_NO_ADDITIONAL_HEADERS, 0, (LPVOID)data.data(), (DWORD)data.size(), (DWORD)data.size(), 0)) {
        return {0, ""};
    }

    if (!WinHttpReceiveResponse(hRequest, NULL)) return {0, ""};

    DWORD statusCode = 0;
    DWORD size = sizeof(statusCode);
    WinHttpQueryHeaders(hRequest, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER, WINHTTP_HEADER_NAME_BY_INDEX, &statusCode, &size, WINHTTP_NO_HEADER_INDEX);

    return {(int)statusCode, ""};
}

}
}
