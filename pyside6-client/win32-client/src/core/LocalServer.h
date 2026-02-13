#pragma once
#include <string>
#include <thread>
#include <atomic>

namespace ClipboardPush {

class LocalServer {
public:
    static LocalServer& Instance();

    void Start();
    void Stop();

    std::string GetIP() const { return m_ip; }
    int GetPort() const { return m_port; }

private:
    LocalServer();
    ~LocalServer();

    void Run();

    std::string m_ip;
    int m_port = 0;
    std::thread m_thread;
    std::atomic<bool> m_running{false};
};

}
