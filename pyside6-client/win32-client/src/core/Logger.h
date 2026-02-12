#pragma once
#include <windows.h>
#include <string>

namespace ClipboardPush {
namespace Logger {

enum class Level { Debug, Info, Warning, Error };

void Init(const std::string& logFileName = "clipboard_push.log");
void Log(Level level, const char* format, ...);
void Shutdown();

}
}

#define LOG_DEBUG(...)   ClipboardPush::Logger::Log(ClipboardPush::Logger::Level::Debug, __VA_ARGS__)
#define LOG_INFO(...)    ClipboardPush::Logger::Log(ClipboardPush::Logger::Level::Info, __VA_ARGS__)
#define LOG_WARNING(...) ClipboardPush::Logger::Log(ClipboardPush::Logger::Level::Warning, __VA_ARGS__)
#define LOG_ERROR(...)   ClipboardPush::Logger::Log(ClipboardPush::Logger::Level::Error, __VA_ARGS__)
