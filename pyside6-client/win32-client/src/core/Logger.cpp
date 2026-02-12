#include "Logger.h"
#include <cstdio>
#include <cstdarg>
#include <ctime>
#include <mutex>
#include <filesystem>
#include <chrono>

namespace ClipboardPush {
namespace Logger {

static FILE* g_logFile = nullptr;
static std::string g_logPath;
static std::mutex g_logMutex;
static const size_t MAX_LOG_SIZE = 1024 * 1024; // 1MB

void RotateLogs() {
    if (g_logFile) fclose(g_logFile);
    
    std::string oldLog = g_logPath + ".old";
    std::error_code ec;
    std::filesystem::rename(g_logPath, oldLog, ec);
    
    g_logFile = _fsopen(g_logPath.c_str(), "w", _SH_DENYNO);
}

void Init(const std::string& logFileName) {
    std::lock_guard<std::mutex> lock(g_logMutex);
    
    wchar_t buffer[MAX_PATH];
    GetModuleFileNameW(NULL, buffer, MAX_PATH);
    std::filesystem::path path(buffer);
    g_logPath = (path.parent_path() / logFileName).string();

    g_logFile = _fsopen(g_logPath.c_str(), "a", _SH_DENYNO);
}

void Log(Level level, const char* format, ...) {
    std::lock_guard<std::mutex> lock(g_logMutex);

    // Check size for rotation
    if (g_logFile) {
        long size = ftell(g_logFile);
        if (size > (long)MAX_LOG_SIZE) {
            RotateLogs();
        }
    }

    char buffer[4096];
    
    // 1. Timestamp
    auto now = std::chrono::system_clock::now();
    auto time_t_now = std::chrono::system_clock::to_time_t(now);
    std::tm tm_now;
    localtime_s(&tm_now, &time_t_now);
    
    int len = snprintf(buffer, sizeof(buffer), "[%04d-%02d-%02d %02d:%02d:%02d] ", 
        tm_now.tm_year + 1900, tm_now.tm_mon + 1, tm_now.tm_mday,
        tm_now.tm_hour, tm_now.tm_min, tm_now.tm_sec);

    // 2. Level
    const char* levelStr = "INFO";
    switch (level) {
        case Level::Debug:   levelStr = "DEBUG"; break;
        case Level::Warning: levelStr = "WARN"; break;
        case Level::Error:   levelStr = "ERROR"; break;
    }
    len += snprintf(buffer + len, sizeof(buffer) - len, "[%s] ", levelStr);

    // 3. Message
    va_list args;
    va_start(args, format);
    len += vsnprintf(buffer + len, sizeof(buffer) - len, format, args);
    va_end(args);

    // Output to Console/Debug
    OutputDebugStringA(buffer);
    OutputDebugStringA("\n");
    printf("%s\n", buffer);

    // Output to File
    if (g_logFile) {
        fprintf(g_logFile, "%s\n", buffer);
        fflush(g_logFile);
    }
}

void Shutdown() {
    std::lock_guard<std::mutex> lock(g_logMutex);
    if (g_logFile) {
        fclose(g_logFile);
        g_logFile = nullptr;
    }
}

}
}
