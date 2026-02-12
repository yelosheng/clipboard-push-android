#pragma once
#include <string>
#include <windows.h>

namespace ClipboardPush {
namespace Utils {

inline std::wstring ToWide(const std::string& str) {
    if (str.empty()) return std::wstring();
    int size_needed = MultiByteToWideChar(CP_UTF8, 0, str.c_str(), (int)str.size(), NULL, 0);
    if (size_needed <= 0) return std::wstring();
    std::wstring wstrTo(size_needed, 0);
    MultiByteToWideChar(CP_UTF8, 0, str.c_str(), (int)str.size(), &wstrTo[0], size_needed);
    return wstrTo;
}

inline std::string ToUtf8(const std::wstring& wstr) {
    if (wstr.empty()) return std::string();
    int size_needed = WideCharToMultiByte(CP_UTF8, 0, wstr.c_str(), (int)wstr.size(), NULL, 0, NULL, NULL);
    if (size_needed <= 0) return std::string();
    std::string strTo(size_needed, 0);
    WideCharToMultiByte(CP_UTF8, 0, wstr.c_str(), (int)wstr.size(), &strTo[0], size_needed, NULL, NULL);
    return strTo;
}

inline bool SetAutoStart(bool enabled) {
    HKEY hKey;
    const wchar_t* path = L"Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    const wchar_t* name = L"ClipboardPushWin32";
    
    if (RegOpenKeyExW(HKEY_CURRENT_USER, path, 0, KEY_SET_VALUE, &hKey) != ERROR_SUCCESS) {
        return false;
    }

    if (enabled) {
        wchar_t appPath[MAX_PATH];
        GetModuleFileNameW(NULL, appPath, MAX_PATH);
        std::wstring cmd = L"\"" + std::wstring(appPath) + L"\"";
        RegSetValueExW(hKey, name, 0, REG_SZ, (BYTE*)cmd.c_str(), (DWORD)((cmd.length() + 1) * sizeof(wchar_t)));
    } else {
        RegDeleteValueW(hKey, name);
    }

    RegCloseKey(hKey);
    return true;
}

}
}
