#include "Application.h"
#include "core/Logger.h"

#include <QApplication>

#ifdef _WIN32
#include <windows.h>
#endif

int main(int argc, char* argv[]) {
    // Initialize logger
    ClipboardPush::Logger::init();

    LOG_INFO("Clipboard Push starting...");

#ifdef _WIN32
    // Enable high DPI support
    SetProcessDPIAware();
#endif

    QApplication app(argc, argv);
    app.setApplicationName("Clipboard Push");
    app.setApplicationVersion("1.0.0");
    app.setOrganizationName("ClipboardPush");

    ClipboardPush::Application application(&app);
    int result = application.run();

    ClipboardPush::Logger::shutdown();
    return result;
}
