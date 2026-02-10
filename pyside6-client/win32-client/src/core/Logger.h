#pragma once
#include <windows.h>
#include <cstdio>
#include <cstdarg>

namespace ClipboardPush {
namespace Logger {

enum class Level { Debug, Info, Error };

inline void Log(Level level, const char* format, ...) {
    char buffer[2048];
    
    // Add prefix
    const char* prefix = "[INFO] ";
    if (level == Level::Debug) prefix = "[DEBUG] ";
    else if (level == Level::Error) prefix = "[ERROR] ";
    
    int len = snprintf(buffer, sizeof(buffer), "%s", prefix);
    
    va_list args;
    va_start(args, format);
    vsnprintf(buffer + len, sizeof(buffer) - len, format, args);
    va_end(args);

    OutputDebugStringA(buffer);
    OutputDebugStringA("\n");
    
    // Also print to console
    printf("%s\n", buffer);
    fflush(stdout);
}

}
}

#define LOG_INFO(...) ClipboardPush::Logger::Log(ClipboardPush::Logger::Level::Info, __VA_ARGS__)
#define LOG_ERROR(...) ClipboardPush::Logger::Log(ClipboardPush::Logger::Level::Error, __VA_ARGS__)

#ifdef _DEBUG
#define LOG_DEBUG(...) ClipboardPush::Logger::Log(ClipboardPush::Logger::Level::Debug, __VA_ARGS__)
#else
#define LOG_DEBUG(...) ((void)0)
#endif