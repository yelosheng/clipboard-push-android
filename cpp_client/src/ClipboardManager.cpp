#include "ClipboardManager.hpp"
#include <codecvt> // For wstring/string conversion (C++17 deprecated but common, or use Win32 MultiByteToWideChar)
#include <shlobj.h>
#include <spdlog/spdlog.h>
#include <windows.h>


// Helper: UTF-8 to Wide
std::wstring ToWide(const std::string &str) {
  if (str.empty())
    return L"";
  int size_needed =
      MultiByteToWideChar(CP_UTF8, 0, &str[0], (int)str.size(), NULL, 0);
  std::wstring wstrTo(size_needed, 0);
  MultiByteToWideChar(CP_UTF8, 0, &str[0], (int)str.size(), &wstrTo[0],
                      size_needed);
  return wstrTo;
}

// Helper: Wide to UTF-8
std::string ToUtf8(const std::wstring &wstr) {
  if (wstr.empty())
    return "";
  int size_needed = WideCharToMultiByte(CP_UTF8, 0, &wstr[0], (int)wstr.size(),
                                        NULL, 0, NULL, NULL);
  std::string strTo(size_needed, 0);
  WideCharToMultiByte(CP_UTF8, 0, &wstr[0], (int)wstr.size(), &strTo[0],
                      size_needed, NULL, NULL);
  return strTo;
}

ClipboardManager &ClipboardManager::Instance() {
  static ClipboardManager instance;
  return instance;
}

void ClipboardManager::SetOnClipboardChange(std::function<void()> callback) {
  m_callback = callback;
}

void ClipboardManager::ProcessClipboardChange() {
  if (m_callback)
    m_callback();
}

void ClipboardManager::SetText(const std::string &text) {
  if (!OpenClipboard(NULL))
    return;
  EmptyClipboard();

  std::wstring wtext = ToWide(text);
  HGLOBAL hGlob =
      GlobalAlloc(GMEM_MOVEABLE, (wtext.length() + 1) * sizeof(wchar_t));
  if (hGlob) {
    memcpy(GlobalLock(hGlob), wtext.c_str(),
           (wtext.length() + 1) * sizeof(wchar_t));
    GlobalUnlock(hGlob);
    SetClipboardData(CF_UNICODETEXT, hGlob);
  }
  CloseClipboard();
}

std::string ClipboardManager::GetText() {
  if (!OpenClipboard(NULL))
    return "";

  std::string result;
  HANDLE hData = GetClipboardData(CF_UNICODETEXT);
  if (hData) {
    wchar_t *pszText = static_cast<wchar_t *>(GlobalLock(hData));
    if (pszText) {
      result = ToUtf8(pszText);
      GlobalUnlock(hData);
    }
  }
  CloseClipboard();
  return result;
}

void ClipboardManager::SetFiles(const std::vector<std::string> &files) {
  std::vector<std::wstring> wPaths;
  for (const auto &f : files) {
    wPaths.push_back(ToWide(f));
  }
  SetFilesWin32(wPaths);
}

std::vector<std::string> ClipboardManager::GetFiles() {
  auto wFiles = GetFilesWin32();
  std::vector<std::string> files;
  for (const auto &wf : wFiles) {
    files.push_back(ToUtf8(wf));
  }
  return files;
}

void ClipboardManager::SetFilesWin32(const std::vector<std::wstring> &paths) {
  if (paths.empty())
    return;
  if (!OpenClipboard(NULL))
    return;
  EmptyClipboard();

  size_t totalLen = sizeof(DROPFILES);
  for (const auto &p : paths) {
    totalLen += (p.length() + 1) * sizeof(wchar_t);
  }
  totalLen += sizeof(wchar_t); // Double null

  HGLOBAL hGlob = GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, totalLen);
  if (!hGlob) {
    CloseClipboard();
    return;
  }

  BYTE *pData = (BYTE *)GlobalLock(hGlob);
  DROPFILES *df = (DROPFILES *)pData;
  df->pFiles = sizeof(DROPFILES);
  df->fWide = TRUE;
  df->pt.x = 0;
  df->pt.y = 0;
  df->fNC = FALSE;

  BYTE *pPathData = pData + sizeof(DROPFILES);
  for (const auto &p : paths) {
    size_t bytes = (p.length() + 1) * sizeof(wchar_t);
    memcpy(pPathData, p.c_str(), bytes);
    pPathData += bytes;
  }

  GlobalUnlock(hGlob);
  SetClipboardData(CF_HDROP, hGlob);
  CloseClipboard();
}

std::vector<std::wstring> ClipboardManager::GetFilesWin32() {
  std::vector<std::wstring> files;
  if (!OpenClipboard(NULL))
    return files;

  if (IsClipboardFormatAvailable(CF_HDROP)) {
    HDROP hDrop = (HDROP)GetClipboardData(CF_HDROP);
    if (hDrop) {
      UINT count = DragQueryFileW(hDrop, 0xFFFFFFFF, NULL, 0);
      for (UINT i = 0; i < count; i++) {
        UINT len = DragQueryFileW(hDrop, i, NULL, 0);
        std::wstring wpath(len + 1, L'\0');
        DragQueryFileW(hDrop, i, &wpath[0], len + 1);
        wpath.resize(len);
        files.push_back(wpath);
      }
    }
  }
  CloseClipboard();
  return files;
}
