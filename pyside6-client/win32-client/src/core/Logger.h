#pragma once
#include <windows.h>
#include <cstdio>
#include <cstdarg>

namespace ClipboardPush {
namespace Logger {

inline void Log(const char* format, ...) {
    char buffer[4096];
    va_list args;
    va_start(args, format);
    vsnprintf(buffer, sizeof(buffer), format, args);
    va_end(args);

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
