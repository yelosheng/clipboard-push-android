cmake_minimum_required(VERSION 3.21)
project(ClipboardPushWin32 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Use static runtime for portability (Optional, increases size but standalone)
# set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

# Dependencies
find_package(nlohmann_json CONFIG REQUIRED)
find_package(unofficial-nayuki-qr-code-generator CONFIG REQUIRED)

# System Libraries
# Note: CMake automatically links kernel32, user32, gdi32, winspool, shell32, ole32, oleaut32, uuid, comdlg32, advapi32
# We need to explicitly add others like WinHTTP, BCrypt, GDI+
set(SYSTEM_LIBS
    winhttp
    bcrypt
    gdiplus
    crypt32
    shlwapi
)

# Source files
set(SOURCES
    src/main.cpp
    src/core/Config.cpp
    src/core/Crypto.cpp
    src/core/Logger.cpp
    src/core/Network.cpp
    src/core/SocketIOService.cpp
    src/platform/Platform.cpp
    src/platform/Clipboard.cpp
    src/platform/ClipboardMonitor.cpp
    src/platform/Hotkey.cpp
    src/ui/TrayIcon.cpp
    src/ui/MainWindow.cpp
    src/ui/SettingsWindow.cpp
)

# Resources
set(RESOURCES src/ClipboardPush.rc)

add_executable(ClipboardPushWin32 WIN32
    ${SOURCES}
    ${RESOURCES}
)

target_link_libraries(ClipboardPushWin32 PRIVATE
    nlohmann_json::nlohmann_json
    unofficial::nayuki-qr-code-generator::nayuki-qr-code-generator
    ${SYSTEM_LIBS}
)

target_include_directories(ClipboardPushWin32 PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/src
)

# Compilation options for size
if(MSVC)
    target_compile_options(ClipboardPushWin32 PRIVATE
        $<$<CONFIG:Release>:/O1 /Ob2 /Oi /Os /GL>
    )
    target_link_options(ClipboardPushWin32 PRIVATE
        $<$<CONFIG:Release>:/LTCG /OPT:REF /OPT:ICF>
    )
endif()

target_compile_definitions(ClipboardPushWin32 PRIVATE NOMINMAX)
