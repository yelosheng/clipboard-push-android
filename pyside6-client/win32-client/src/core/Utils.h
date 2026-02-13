#pragma once

// MUST include winsock2 before windows.h
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <winsock2.h>
#include <ws2tcpip.h>
#include <iphlpapi.h>
#include <windows.h>

#include <string>
#include <vector>

#pragma comment(lib, "iphlpapi.lib")
#pragma comment(lib, "ws2_32.lib")

namespace ClipboardPush {
namespace Utils {

struct NetworkMetadata {
    std::string private_ip = "127.0.0.1";
    std::string cidr = "127.0.0.1/32";
    std::string network_id_hash = "";
    int network_epoch = 0;
};

inline NetworkMetadata GetNetworkMetadata() {
    ULONG outBufLen = 15000;
    std::vector<uint8_t> buffer(outBufLen);
    PIP_ADAPTER_ADDRESSES pAddresses = reinterpret_cast<PIP_ADAPTER_ADDRESSES>(buffer.data());

    NetworkMetadata meta;
    int bestScore = -1;

    if (GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER, NULL, pAddresses, &outBufLen) == ERROR_SUCCESS) {
        for (PIP_ADAPTER_ADDRESSES pCurrAddresses = pAddresses; pCurrAddresses != NULL; pCurrAddresses = pCurrAddresses->Next) {
            if (pCurrAddresses->OperStatus != IfOperStatusUp) continue;
            if (pCurrAddresses->IfType == IF_TYPE_SOFTWARE_LOOPBACK) continue;
            
            std::wstring desc = pCurrAddresses->Description;
            if (desc.find(L"VMware") != std::wstring::npos || desc.find(L"VirtualBox") != std::wstring::npos) continue;

            for (PIP_ADAPTER_UNICAST_ADDRESS pUnicast = pCurrAddresses->FirstUnicastAddress; pUnicast != NULL; pUnicast = pUnicast->Next) {
                sockaddr_in* sa_in = reinterpret_cast<sockaddr_in*>(pUnicast->Address.lpSockaddr);
                char addr[INET_ADDRSTRLEN];
                inet_ntop(AF_INET, &(sa_in->sin_addr), addr, INET_ADDRSTRLEN);
                
                std::string ip(addr);
                if (ip == "127.0.0.1") continue;

                int score = 0;
                if (ip.substr(0, 8) == "192.168.") score = 100;
                else if (ip.substr(0, 3) == "10." && ip.substr(0, 7) != "10.100.") score = 90;
                else if (ip.substr(0, 4) == "172.") score = 80;
                else if (ip.substr(0, 7) == "100.64.") score = 10;
                else score = 50;

                if (score > bestScore) {
                    bestScore = score;
                    meta.private_ip = ip;
                    
                    // Calculate CIDR suffix manually
                    meta.cidr = ip + "/" + std::to_string(pUnicast->OnLinkPrefixLength);
                    
                    // Use AdapterName as a simple unique network ID (GUID string)
                    meta.network_id_hash = pCurrAddresses->AdapterName;
                }
            }
        }
    }
    return meta;
}

inline std::string GetLocalIPAddress() {
    return GetNetworkMetadata().private_ip;
}

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

inline std::wstring GetAppDir() {
    wchar_t buffer[MAX_PATH];
    GetModuleFileNameW(NULL, buffer, MAX_PATH);
    std::wstring path(buffer);
    return path.substr(0, path.find_last_of(L"\\/"));
}

}
}
