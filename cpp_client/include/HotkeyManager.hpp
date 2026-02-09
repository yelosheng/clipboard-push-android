#pragma once
#include <functional>
#include <map>
#include <windows.h>

class HotkeyManager {
public:
  static HotkeyManager &Instance();

  // Register a hotkey. Returns a unique ID for the hotkey.
  // mod: MOD_ALT, MOD_CONTROL, MOD_SHIFT, MOD_WIN
  // key: Virtual Key Code (e.g. 'V', VK_F1)
  // callback: function to call when hotkey triggers
  int Register(int mod, int key, std::function<void()> callback);

  void Unregister(int id);
  void UnregisterAll();

  // To be called from WinMain message loop
  void HandleHotkey(int id);

private:
  HotkeyManager() = default;

  std::map<int, std::function<void()>> m_callbacks;
  int m_nextId = 1;
};
