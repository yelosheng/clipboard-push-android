#include "Clipboard.h"
#include "core/Utils.h"
#include "core/Logger.h"
#include <winsock2.h>
#include <windows.h>
#include <shellapi.h>
#include <shlobj.h>
#include <shlwapi.h>
#include <gdiplus.h>
#include <algorithm>

namespace ClipboardPush {
namespace Platform {

// Helper for GDI+ Encoder
int GetEncoderClsid(const WCHAR* format, CLSID* pClsid) {
    UINT num = 0;
    UINT size = 0;
    Gdiplus::GetImageEncodersSize(&num, &size);
    if (size == 0) return -1;
    std::vector<char> buffer(size);
    Gdiplus::ImageCodecInfo* pImageCodecInfo = (Gdiplus::ImageCodecInfo*)(buffer.data());
    Gdiplus::GetImageEncoders(num, size, pImageCodecInfo);
    for (UINT j = 0; j < num; ++j) {
        if (wcscmp(pImageCodecInfo[j].MimeType, format) == 0) {
            *pClsid = pImageCodecInfo[j].Clsid;
            return j;
        }
    }
    return -1;
}

ClipboardData Clipboard::Get() {
    ClipboardData data;
    if (!OpenClipboard(NULL)) return data;

    if (IsClipboardFormatAvailable(CF_HDROP)) {
        data.type = ClipboardType::Files;
        HDROP hDrop = (HDROP)GetClipboardData(CF_HDROP);
        if (hDrop) {
            UINT count = DragQueryFileW(hDrop, 0xFFFFFFFF, NULL, 0);
            for (UINT i = 0; i < count; i++) {
                UINT len = DragQueryFileW(hDrop, i, NULL, 0);
                std::wstring path(len + 1, 0);
                DragQueryFileW(hDrop, i, &path[0], len + 1);
                path.resize(len); // Remove null
                data.files.push_back(Utils::ToUtf8(path));
            }
        }
    }
    else if (IsClipboardFormatAvailable(CF_BITMAP)) {
        data.type = ClipboardType::Image;
        HBITMAP hBmp = (HBITMAP)GetClipboardData(CF_BITMAP);
        if (hBmp) {
            Gdiplus::Bitmap bmp(hBmp, NULL);
            IStream* stream = NULL;
            if (CreateStreamOnHGlobal(NULL, TRUE, &stream) == S_OK) {
                CLSID pngClsid;
                GetEncoderClsid(L"image/png", &pngClsid);
                bmp.Save(stream, &pngClsid, NULL);
                
                // Read stream
                STATSTG stat;
                stream->Stat(&stat, STATFLAG_NONAME);
                data.image_data.resize((size_t)stat.cbSize.QuadPart);
                LARGE_INTEGER li = {0};
                stream->Seek(li, STREAM_SEEK_SET, NULL);
                ULONG read;
                stream->Read(data.image_data.data(), (ULONG)data.image_data.size(), &read);
                stream->Release();
            }
        }
    }
    else if (IsClipboardFormatAvailable(CF_UNICODETEXT)) {
        data.type = ClipboardType::Text;
        HANDLE hMem = GetClipboardData(CF_UNICODETEXT);
        if (hMem) {
            void* ptr = GlobalLock(hMem);
            if (ptr) {
                data.text = Utils::ToUtf8((wchar_t*)ptr);
            }
            GlobalUnlock(hMem);
        }
    }

    CloseClipboard();
    return data;
}

bool Clipboard::SetText(const std::string& text) {
    if (!OpenClipboard(NULL)) return false;
    EmptyClipboard();

    std::wstring wtext = Utils::ToWide(text);
    size_t size = (wtext.length() + 1) * sizeof(wchar_t);
    HGLOBAL hMem = GlobalAlloc(GMEM_MOVEABLE, size);
    if (hMem) {
        void* ptr = GlobalLock(hMem);
        if (ptr) {
            memcpy(ptr, wtext.c_str(), size);
            GlobalUnlock(hMem);
            SetClipboardData(CF_UNICODETEXT, hMem);
        } else {
            GlobalFree(hMem);
        }
    }
    CloseClipboard();
    return true;
}

bool Clipboard::SetFiles(const std::vector<std::string>& files) {
    if (!OpenClipboard(NULL)) return false;
    EmptyClipboard();

    // Calculate size: DROPFILES + (path1\0path2\0...pathN\0\0)
    size_t pathsLen = 0;
    std::vector<std::wstring> wfiles;
    for (const auto& f : files) {
        std::wstring wf = Utils::ToWide(f);
        wfiles.push_back(wf);
        pathsLen += (wf.length() + 1) * sizeof(wchar_t);
    }
    pathsLen += sizeof(wchar_t); // Final null

    size_t totalSize = sizeof(DROPFILES) + pathsLen;
    HGLOBAL hMem = GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, totalSize);
    if (hMem) {
        char* ptr = (char*)GlobalLock(hMem);
        if (ptr) {
            DROPFILES* df = (DROPFILES*)ptr;
            df->pFiles = sizeof(DROPFILES);
            df->fWide = TRUE;
            
            char* pathPtr = ptr + sizeof(DROPFILES);
            for (const auto& wf : wfiles) {
                size_t bytes = (wf.length() + 1) * sizeof(wchar_t);
                memcpy(pathPtr, wf.c_str(), bytes);
                pathPtr += bytes;
            }
            // Double null is handled by GMEM_ZEROINIT
            
            GlobalUnlock(hMem);
            SetClipboardData(CF_HDROP, hMem);
        } else {
            GlobalFree(hMem);
        }
    }
    CloseClipboard();
    return true;
}

bool Clipboard::SetImage(const std::vector<uint8_t>& pngData) {
    if (pngData.empty()) return false;
    
    IStream* stream = SHCreateMemStream(pngData.data(), (UINT)pngData.size());
    if (!stream) return false;
    
    Gdiplus::Bitmap bmp(stream);
    stream->Release();

    CLSID bmpClsid;
    if (GetEncoderClsid(L"image/bmp", &bmpClsid) < 0) return false;

    IStream* memStream = NULL;
    CreateStreamOnHGlobal(NULL, TRUE, &memStream);
    if (!memStream) return false;

    bmp.Save(memStream, &bmpClsid, NULL);

    HGLOBAL hGlobal = NULL;
    GetHGlobalFromStream(memStream, &hGlobal);
    
    void* pBmpData = GlobalLock(hGlobal);
    size_t bmpSize = GlobalSize(hGlobal);

    // Skip BITMAPFILEHEADER (14 bytes) to get DIB
    if (bmpSize > 14) {
        size_t dibSize = bmpSize - 14;
        HGLOBAL hDib = GlobalAlloc(GMEM_MOVEABLE, dibSize);
        if (hDib) {
            void* pDib = GlobalLock(hDib);
            memcpy(pDib, (char*)pBmpData + 14, dibSize);
            GlobalUnlock(hDib);

            if (OpenClipboard(NULL)) {
                EmptyClipboard();
                SetClipboardData(CF_DIB, hDib);
                CloseClipboard();
            }
        }
    }

    GlobalUnlock(hGlobal);
    memStream->Release();
    return true;
}

bool Clipboard::SetImageFromFile(const std::string& filePath) {
    std::wstring wPath = Utils::ToWide(filePath);
    Gdiplus::Bitmap bmp(wPath.c_str());
    
    CLSID bmpClsid;
    if (GetEncoderClsid(L"image/bmp", &bmpClsid) < 0) return false;

    IStream* memStream = NULL;
    CreateStreamOnHGlobal(NULL, TRUE, &memStream);
    if (!memStream) return false;

    bmp.Save(memStream, &bmpClsid, NULL);

    HGLOBAL hGlobal = NULL;
    GetHGlobalFromStream(memStream, &hGlobal);
    
    void* pBmpData = GlobalLock(hGlobal);
    size_t bmpSize = GlobalSize(hGlobal);

    bool success = false;
    if (bmpSize > 14) {
        size_t dibSize = bmpSize - 14;
        HGLOBAL hDib = GlobalAlloc(GMEM_MOVEABLE, dibSize);
        if (hDib) {
            void* pDib = GlobalLock(hDib);
            memcpy(pDib, (char*)pBmpData + 14, dibSize);
            GlobalUnlock(hDib);

            if (OpenClipboard(NULL)) {
                EmptyClipboard();
                SetClipboardData(CF_DIB, hDib);
                CloseClipboard();
                success = true;
            }
        }
    }

    GlobalUnlock(hGlobal);
    memStream->Release();
    return success;
}

}
}