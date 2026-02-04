#include "Clipboard.hpp"
#include <windows.h>
#include <shlobj.h>
#include <gdiplus.h>
#include <iostream>
#include <fstream>
#include <filesystem>
#include <spdlog/spdlog.h>

// #pragma comment (lib, "Gdiplus.lib") // 已移动到 CMakeLists.txt

// --- Helper Functions ---

std::wstring Clipboard::Utf8ToWide(const std::string& str) {
    if (str.empty()) return L"";
    int size_needed = MultiByteToWideChar(CP_UTF8, 0, &str[0], (int)str.size(), NULL, 0);
    std::wstring wstrTo(size_needed, 0);
    MultiByteToWideChar(CP_UTF8, 0, &str[0], (int)str.size(), &wstrTo[0], size_needed);
    return wstrTo;
}

std::string Clipboard::WideToUtf8(const std::wstring& wstr) {
    if (wstr.empty()) return "";
    int size_needed = WideCharToMultiByte(CP_UTF8, 0, &wstr[0], (int)wstr.size(), NULL, 0, NULL, NULL);
    std::string strTo(size_needed, 0);
    WideCharToMultiByte(CP_UTF8, 0, &wstr[0], (int)wstr.size(), &strTo[0], size_needed, NULL, NULL);
    return strTo;
}

// --- Implementation ---

ClipboardType Clipboard::GetType() {
    if (!OpenClipboard(nullptr)) return ClipboardType::None;
    
    ClipboardType type = ClipboardType::None;
    if (IsClipboardFormatAvailable(CF_HDROP)) type = ClipboardType::FileList;
    else if (IsClipboardFormatAvailable(CF_UNICODETEXT)) type = ClipboardType::Text;
    else if (IsClipboardFormatAvailable(CF_DIB)) type = ClipboardType::Image;
    
    CloseClipboard();
    return type;
}

bool Clipboard::SetText(const std::string& text) {
    if (!OpenClipboard(nullptr)) return false;
    EmptyClipboard();

    std::wstring wtext = Utf8ToWide(text);
    HGLOBAL hGlob = GlobalAlloc(GMEM_MOVEABLE, (wtext.size() + 1) * sizeof(wchar_t));
    if (!hGlob) { CloseClipboard(); return false; }

    memcpy(GlobalLock(hGlob), wtext.c_str(), (wtext.size() + 1) * sizeof(wchar_t));
    GlobalUnlock(hGlob);

    SetClipboardData(CF_UNICODETEXT, hGlob);
    CloseClipboard();
    return true;
}

std::optional<std::string> Clipboard::GetText() {
    if (!OpenClipboard(nullptr)) return std::nullopt;
    
    std::optional<std::string> result = std::nullopt;
    if (IsClipboardFormatAvailable(CF_UNICODETEXT)) {
        HGLOBAL hGlob = GetClipboardData(CF_UNICODETEXT);
        if (hGlob) {
            wchar_t* wtext = (wchar_t*)GlobalLock(hGlob);
            if (wtext) {
                result = WideToUtf8(wtext);
                GlobalUnlock(hGlob);
            }
        }
    }
    CloseClipboard();
    return result;
}

bool Clipboard::SetFiles(const std::vector<std::string>& filePaths) {
    if (filePaths.empty()) return false;
    if (!OpenClipboard(nullptr)) return false;
    EmptyClipboard();

    // Calculate buffer size
    size_t totalLen = sizeof(DROPFILES);
    std::vector<std::wstring> wPaths;
    for (const auto& path : filePaths) {
        std::wstring wpath = Utf8ToWide(path);
        wPaths.push_back(wpath);
        totalLen += (wpath.length() + 1) * sizeof(wchar_t);
    }
    totalLen += sizeof(wchar_t); // Double null terminator

    HGLOBAL hGlob = GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, totalLen);
    if (!hGlob) { CloseClipboard(); return false; }

    BYTE* pData = (BYTE*)GlobalLock(hGlob);
    DROPFILES* df = (DROPFILES*)pData;
    df->pFiles = sizeof(DROPFILES);
    df->fWide = TRUE;

    BYTE* pPathData = pData + sizeof(DROPFILES);
    for (const auto& wpath : wPaths) {
        size_t bytes = (wpath.length() + 1) * sizeof(wchar_t);
        memcpy(pPathData, wpath.c_str(), bytes);
        pPathData += bytes;
    }

    GlobalUnlock(hGlob);
    SetClipboardData(CF_HDROP, hGlob);
    CloseClipboard();
    return true;
}

std::vector<std::string> Clipboard::GetFiles() {
    std::vector<std::string> files;
    if (!OpenClipboard(nullptr)) return files;

    if (IsClipboardFormatAvailable(CF_HDROP)) {
        HDROP hDrop = (HDROP)GetClipboardData(CF_HDROP);
        if (hDrop) {
            UINT count = DragQueryFileW(hDrop, 0xFFFFFFFF, NULL, 0);
            for (UINT i = 0; i < count; i++) {
                UINT len = DragQueryFileW(hDrop, i, NULL, 0);
                std::wstring wpath(len + 1, L'\0');
                DragQueryFileW(hDrop, i, &wpath[0], len + 1);
                wpath.resize(len); // Remove null
                files.push_back(WideToUtf8(wpath));
            }
        }
    }
    CloseClipboard();
    return files;
}

// GDI+ Boilerplate helper
int GetEncoderClsid(const WCHAR* format, CLSID* pClsid) {
    UINT  num = 0;          // number of image encoders
    UINT  size = 0;         // size of the image encoder array in bytes
    Gdiplus::GetImageEncodersSize(&num, &size);
    if (size == 0) return -1;
    Gdiplus::ImageCodecInfo* pImageCodecInfo = (Gdiplus::ImageCodecInfo*)(malloc(size));
    if (pImageCodecInfo == NULL) return -1;
    Gdiplus::GetImageEncoders(num, size, pImageCodecInfo);
    for (UINT j = 0; j < num; ++j) {
        if (wcscmp(pImageCodecInfo[j].MimeType, format) == 0) {
            *pClsid = pImageCodecInfo[j].Clsid;
            free(pImageCodecInfo);
            return j;
        }
    }
    free(pImageCodecInfo);
    return -1;
}

bool Clipboard::SetImageFromFile(const std::string& imagePath) {
    // Win32 CF_DIB setting is complex. 
    // Simplified strategy: Load file using GDI+, Save to Stream as BMP, skip header, set CF_DIB.
    // For now, this is a placeholder. Implementing robust C++ image clipboard copy is huge.
    // A workaround is to use SetFiles (CF_HDROP) which most apps (WeChat, etc) also accept.
    return SetFiles({imagePath});
}

std::optional<std::string> Clipboard::GetImageToTempFile() {
    if (!OpenClipboard(nullptr)) return std::nullopt;
    
    std::optional<std::string> result = std::nullopt;
    
    // Check for CF_DIB
    HANDLE hDib = GetClipboardData(CF_DIB);
    if (hDib) {
         // Initialize GDI+
        Gdiplus::GdiplusStartupInput gdiplusStartupInput;
        ULONG_PTR gdiplusToken;
        Gdiplus::GdiplusStartup(&gdiplusToken, &gdiplusStartupInput, NULL);

        {
            // Create bitmap from DIB handle
            // Gdiplus::Bitmap::FromBITMAPINFO is not directly available, need to wrap
            // This is complex. 
            // Alternative: Save raw clipboard data (DIB) to a BMP file manually.
            
            void* pDib = GlobalLock(hDib);
            if (pDib) {
                BITMAPINFOHEADER* bmi = (BITMAPINFOHEADER*)pDib;
                DWORD dibSize = GlobalSize(hDib);
                
                // Create temp file
                char tempPath[MAX_PATH];
                GetTempPathA(MAX_PATH, tempPath);
                std::string outFile = std::string(tempPath) + "clipboard_man_" + std::to_string(GetTickCount()) + ".bmp";
                
                std::ofstream f(outFile, std::ios::binary);
                
                // Construct BMP File Header
                BITMAPFILEHEADER bmfHeader;
                bmfHeader.bfType = 0x4D42; // "BM"
                bmfHeader.bfSize = sizeof(BITMAPFILEHEADER) + dibSize;
                bmfHeader.bfReserved1 = 0;
                bmfHeader.bfReserved2 = 0;
                bmfHeader.bfOffBits = (DWORD)sizeof(BITMAPFILEHEADER) + bmi->biSize + 
                                     (bmi->biClrUsed ? bmi->biClrUsed : (bmi->biBitCount <= 8 ? (1 << bmi->biBitCount) : 0)) * sizeof(RGBQUAD);
                                     
                if (bmi->biCompression == BI_BITFIELDS) bmfHeader.bfOffBits += 12;

                f.write((char*)&bmfHeader, sizeof(BITMAPFILEHEADER));
                f.write((char*)pDib, dibSize);
                f.close();
                
                result = outFile;
                GlobalUnlock(hDib);
            }
        }
        
        Gdiplus::GdiplusShutdown(gdiplusToken);
    }
    
    CloseClipboard();
    return result;
}
