#include "Config.hpp"
#include "Network.hpp"
#include "Clipboard.hpp"
#include <windows.h>
#include <spdlog/spdlog.h>
#include <iostream>

#define HOTKEY_ID 1

void ProcessPush(NetworkClient& client) {
    spdlog::info("Hotkey triggered! Checking clipboard...");
    
    ClipboardType type = Clipboard::GetType();
    
    if (type == ClipboardType::Text) {
        auto text = Clipboard::GetText();
        if (text) {
            spdlog::info("Pushing text: {}...", text->substr(0, 20));
            if (client.PushText(*text)) spdlog::info("Push success");
            else spdlog::error("Push failed");
        }
    } 
    else if (type == ClipboardType::FileList) {
        auto files = Clipboard::GetFiles();
        for (const auto& path : files) {
            std::filesystem::path p(path);
            spdlog::info("Pushing file: {}", p.filename().string());
            if (client.PushFile(path, p.filename().string())) spdlog::info("Push success");
            else spdlog::error("Push failed");
        }
    }
    else if (type == ClipboardType::Image) {
        // Handle image as temp file
        auto tempFile = Clipboard::GetImageToTempFile();
        if (tempFile) {
            spdlog::info("Pushing image from clipboard...");
            if (client.PushFile(*tempFile, "clipboard_image.bmp")) spdlog::info("Push success");
            else spdlog::error("Push failed");
            
            // Cleanup? maybe later
        }
    }
    else {
        spdlog::warn("Clipboard empty or unknown format");
    }
}

int main() {
    // 1. 初始化 Log
    spdlog::set_pattern("[%H:%M:%S %z] [%^%l%$] %v");
    spdlog::info("Starting Clipboard Man C++ Client...");

    // 2. 加载配置
    AppConfig config = AppConfig::Load();
    
    // 3. 启动网络客户端
    NetworkClient client(config);
    client.Start();

    // 4. 注册热键 (Ctrl + Alt + V)
    if (RegisterHotKey(NULL, HOTKEY_ID, MOD_CONTROL | MOD_ALT, 'V')) {
        spdlog::info("Global Hotkey Registered: Ctrl + Alt + V");
    } else {
        spdlog::error("Failed to register hotkey!");
    }

    spdlog::info("Running... Press Ctrl+C to exit.");

    // 5. 消息循环 (必须有消息循环才能响应 Hotkey)
    MSG msg = {0};
    while (GetMessage(&msg, NULL, 0, 0) != 0) {
        if (msg.message == WM_HOTKEY) {
            if (msg.wParam == HOTKEY_ID) {
                ProcessPush(client);
            }
        }
        TranslateMessage(&msg);
        DispatchMessage(&msg);
    }

    client.Stop();
    return 0;
}
