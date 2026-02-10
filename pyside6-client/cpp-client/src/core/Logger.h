#pragma once

#include <spdlog/spdlog.h>
#include <spdlog/sinks/stdout_color_sinks.h>
#include <spdlog/sinks/rotating_file_sink.h>
#include <memory>
#include <filesystem>

namespace ClipboardPush {

class Logger {
public:
    static void init(const std::string& logDir = "") {
        try {
            std::vector<spdlog::sink_ptr> sinks;

            // Console sink with colors
            auto consoleSink = std::make_shared<spdlog::sinks::stdout_color_sink_mt>();
            consoleSink->set_level(spdlog::level::debug);
            consoleSink->set_pattern("[%Y-%m-%d %H:%M:%S.%e] [%^%l%$] %v");
            sinks.push_back(consoleSink);

            // File sink (rotating, 5MB max, 3 files)
            std::string logPath = logDir.empty()
                ? "clipboard_push.log"
                : (std::filesystem::path(logDir) / "clipboard_push.log").string();
            auto fileSink = std::make_shared<spdlog::sinks::rotating_file_sink_mt>(
                logPath, 5 * 1024 * 1024, 3);
            fileSink->set_level(spdlog::level::debug);
            fileSink->set_pattern("[%Y-%m-%d %H:%M:%S.%e] [%l] [%s:%#] %v");
            sinks.push_back(fileSink);

            auto logger = std::make_shared<spdlog::logger>("clipboard_push", sinks.begin(), sinks.end());
            logger->set_level(spdlog::level::debug);
            logger->flush_on(spdlog::level::info);

            spdlog::set_default_logger(logger);
            spdlog::info("Logger initialized");
        } catch (const spdlog::spdlog_ex& ex) {
            // Fallback to console only
            spdlog::set_pattern("[%Y-%m-%d %H:%M:%S.%e] [%^%l%$] %v");
        }
    }

    static void shutdown() {
        spdlog::shutdown();
    }
};

// Convenience macros
#define LOG_TRACE(...) SPDLOG_TRACE(__VA_ARGS__)
#define LOG_DEBUG(...) SPDLOG_DEBUG(__VA_ARGS__)
#define LOG_INFO(...) SPDLOG_INFO(__VA_ARGS__)
#define LOG_WARN(...) SPDLOG_WARN(__VA_ARGS__)
#define LOG_ERROR(...) SPDLOG_ERROR(__VA_ARGS__)
#define LOG_CRITICAL(...) SPDLOG_CRITICAL(__VA_ARGS__)

} // namespace ClipboardPush
