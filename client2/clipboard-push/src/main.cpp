#include "Config.hpp"
#include "Network.hpp"
#include "Clipboard.hpp"
#include "Gui.hpp"
#include <windows.h>
#include <gdiplus.h>
#include <spdlog/spdlog.h>
#include <spdlog/sinks/basic_file_sink.h>

#define HOTKEY_ID 1

// Global Pointers for Callbacks
NetworkClient* g_Client = nullptr;
Gui* g_GuiPtr = nullptr;
AppConfig g_Config;

void DoPush() {
    spdlog::info("Push triggered by Hotkey/Menu");
    
    ClipboardType type = Clipboard::GetType();
    bool success = false;
    std::wstring msg = L"";

    if (type == ClipboardType::Text) {
        auto text = Clipboard::GetText();
        if (text) success = g_Client->PushText(*text);
        msg = L"Text pushed to server";
    } 
    else if (type == ClipboardType::FileList) {
        auto files = Clipboard::GetFiles();
        for (const auto& path : files) {
            std::filesystem::path p(path);
            if(g_Client->PushFile(path, p.filename().string())) success = true;
        }
        msg = L"Files pushed to server";
    }
    else if (type == ClipboardType::Image) {
        auto tempFile = Clipboard::GetImageToTempFile();
        if (tempFile) {
            if(g_Client->PushFile(*tempFile, "clipboard_image.bmp")) success = true;
            msg = L"Image pushed to server";
        }
    }

    if (success) g_GuiPtr->ShowNotification(L"Push Success", msg);
    else g_GuiPtr->ShowNotification(L"Push Failed", L"Could not push clipboard content");
}

void RegisterAppHotkey() {
    UnregisterHotKey(NULL, HOTKEY_ID);
    if (g_Config.hotkey_key != 0) {
        if (RegisterHotKey(NULL, HOTKEY_ID, g_Config.hotkey_mod | MOD_NOREPEAT, g_Config.hotkey_key)) {
            spdlog::info("Registered Hotkey: MOD={} KEY={}", g_Config.hotkey_mod, g_Config.hotkey_key);
        } else {
            DWORD err = GetLastError();
            spdlog::error("Failed to register hotkey (Error: {}). Key collision?", err);
            // Optional: Notify user
            // MessageBoxW(NULL, L"Failed to register hotkey. It might be used by another app.", L"Warning", MB_ICONWARNING);
        }
    } else {
        spdlog::info("Hotkey disabled (Key is 0)");
    }
}

void ReloadConfig() {
    spdlog::info("Reloading config...");
    g_Client->Restart();
    // Re-register hotkey in case it changed
    RegisterAppHotkey();
}

int WINAPI WinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, LPSTR lpCmdLine, int nCmdShow) {
    // 1. File Logger
    auto logger = spdlog::basic_logger_mt("file_logger", "client.log");
    spdlog::set_default_logger(logger);
    
    // 2. Init GDI+
    Gdiplus::GdiplusStartupInput gdiplusStartupInput;
    ULONG_PTR gdiplusToken;
    Gdiplus::GdiplusStartup(&gdiplusToken, &gdiplusStartupInput, NULL);

    // 3. Config
    g_Config = AppConfig::Load();

    // 3. Network
    NetworkClient client(g_Config);
    g_Client = &client;
    client.SetStatusCallback([](bool connected) {
        if (g_GuiPtr) g_GuiPtr->UpdateStatus(connected);
    });
    client.Start();

    // 4. GUI
    Gui gui(hInstance, g_Config, DoPush, ReloadConfig);
    g_GuiPtr = &gui;
    if (!gui.Init()) {
        MessageBoxW(NULL, L"Failed to init GUI", L"Error", MB_ICONERROR);
        return 1;
    }
    
    // 初始化一次状态
    gui.UpdateStatus(client.IsConnected());

    // 5. Hotkey
    RegisterAppHotkey();

    // 6. Message Loop (handled by GUI)
    gui.Run();

    client.Stop();
    Gdiplus::GdiplusShutdown(gdiplusToken);
    return 0;
}
