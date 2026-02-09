#pragma once
#include <functional>
#include <sio_client.h>
#include <string>
#include <vector>

class NetworkClient {
public:
private:
  NetworkClient();

public:
  ~NetworkClient();

  static NetworkClient &Instance() {
    static NetworkClient instance;
    return instance;
  }

  void Start();
  void Stop();
  void Reconnect();

  // Status
  bool IsConnected() const;

  // Callbacks
  void SetOnConnectionStatusChanged(std::function<void(bool)> callback);
  void SetOnSyncMessageReceived(
      std::function<void(const std::string &, const std::string &)> callback);

private:
  void OnSocketConnected();
  void OnSocketClosed(sio::client::close_reason const &reason);
  void OnSocketFail();
  void OnClipboardSync(sio::event &ev);
  void OnFileSync(sio::event &ev);

  void HandleText(const std::string &content, bool encrypted);
  void HandleFile(const std::string &url, const std::string &filename,
                  const std::string &type);
  void DownloadFile(const std::string &url, const std::string &targetPath,
                    const std::string &type);

  sio::client m_client;
  bool m_connected = false;

  std::function<void(bool)> m_connCallback;
  std::function<void(const std::string &, const std::string &)> m_msgCallback;
};
