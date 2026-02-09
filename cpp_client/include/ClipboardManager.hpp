#pragma once
#include <functional>
#include <string>
#include <vector>


class ClipboardManager {
public:
  static ClipboardManager &Instance();

  void SetText(const std::string &text);
  std::string GetText();

  void SetFiles(const std::vector<std::string> &files);
  std::vector<std::string> GetFiles();

  // Win32 Message Loop Integration
  void ProcessClipboardChange();

  // Callback for changes
  void SetOnClipboardChange(std::function<void()> callback);

private:
  ClipboardManager() = default;

  // Win32 Helpers
  void SetFilesWin32(const std::vector<std::wstring> &paths);
  std::vector<std::wstring> GetFilesWin32();

  std::function<void()> m_callback;
};
