#pragma once
#include <windows.h>
#include <cstdio>
#include <cstdarg>
#include <chrono>
#include <iomanip>
#include <sstream>

namespace ClipboardPush {
namespace Logger {

inline void Log(const char* format, ...) {
    char msg[4096];
    va_list args;
    va_start(args, format);
    vsnprintf(msg, sizeof(msg), format, args);
    va_end(args);

    // Get time
    auto now = std::chrono::system_clock::now();
    auto in_time_t = std::chrono::system_clock::to_time_t(now);
    std::tm tm_struct;
    localtime_s(&tm_struct, &in_time_t);

    char buffer[5000];
    snprintf(buffer, sizeof(buffer), "[%02d:%02d:%02d] %s", 
        tm_struct.tm_hour, tm_struct.tm_min, tm_struct.tm_sec, msg);

    OutputDebugStringA(buffer);
    OutputDebugStringA("\n");
    printf("%s\n", buffer);
}

}
}

#define LOG_DEBUG(...)   ClipboardPush::Logger::Log(__VA_ARGS__)
#define LOG_INFO(...)    ClipboardPush::Logger::Log(__VA_ARGS__)
#define LOG_WARNING(...) ClipboardPush::Logger::Log(__VA_ARGS__)
#define LOG_ERROR(...)   ClipboardPush::Logger::Log(__VA_ARGS__)
