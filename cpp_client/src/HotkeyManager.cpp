#include "HotkeyManager.hpp"
#include "Common.hpp"


HotkeyManager &HotkeyManager::Instance() {
  static HotkeyManager instance;
  return instance;
}

int HotkeyManager::Register(int mod, int key, std::function<void()> callback) {
  if (key == 0)
    return 0;

  int id = m_nextId++;

  // We need the HWND to register hotkey if we want to receive WM_HOTKEY message
  // in window proc. But RegisterHotKey with NULL hwnd posts to the thread
  // message queue. Since our message loop (PeekMessage/GetMessage) is on the
  // same thread, this works perfectly. WM_HOTKEY will be retrieved by
  // GetMessage.

  if (RegisterHotKey(NULL, id, mod, key)) {
    m_callbacks[id] = callback;
    spdlog::info("Registered Hotkey ID {}: Mod={}, Key={}", id, mod, key);
    return id;
  } else {
    spdlog::error("Failed to register hotkey. Error: {}", GetLastError());
    return 0;
  }
}

void HotkeyManager::Unregister(int id) {
  if (m_callbacks.count(id)) {
    UnregisterHotKey(NULL, id);
    m_callbacks.erase(id);
    spdlog::info("Unregistered Hotkey ID {}", id);
  }
}

void HotkeyManager::UnregisterAll() {
  for (auto &pair : m_callbacks) {
    UnregisterHotKey(NULL, pair.first);
  }
  m_callbacks.clear();
}

void HotkeyManager::HandleHotkey(int id) {
  if (m_callbacks.count(id)) {
    m_callbacks[id]();
  }
}
