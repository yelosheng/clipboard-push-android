#pragma once
#include <string>
#include <vector>
#include <map>
#include <functional>
#include <optional>
#include <memory>

namespace ClipboardPush {
namespace Network {

struct HttpResponse {
    int status;
    std::string body;
};

class HttpClient {
public:
    static HttpResponse Post(const std::string& url, const std::string& body, const std::string& contentType = "application/json");
    static HttpResponse Put(const std::string& url, const std::vector<uint8_t>& data);
    static std::optional<std::vector<uint8_t>> Get(const std::string& url);
    static std::optional<std::vector<uint8_t>> GetWithHeaders(const std::string& url, const std::map<std::string, std::string>& headers);
};

class WebSocketClient {
public:
    using OnMessageCallback = std::function<void(const std::string&)>;
    using OnOpenCallback = std::function<void()>;
    using OnCloseCallback = std::function<void()>;
    using OnErrorCallback = std::function<void(const std::string&)>;

    WebSocketClient();
    ~WebSocketClient();

    void Connect(const std::string& url);
    void Send(const std::string& message);
    void Close();

    void SetCallbacks(OnOpenCallback onOpen, OnMessageCallback onMessage, OnCloseCallback onClose, OnErrorCallback onError);

private:
    struct Impl;
    std::unique_ptr<Impl> m_impl;
};

}
}
