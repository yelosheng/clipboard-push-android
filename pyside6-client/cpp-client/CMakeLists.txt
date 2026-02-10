cmake_minimum_required(VERSION 3.21)
project(ClipboardPush VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)
set(CMAKE_AUTOUIC ON)

# Find required packages
find_package(Qt6 REQUIRED COMPONENTS Core Gui Widgets Network WebSockets)
find_package(OpenSSL REQUIRED)
find_package(nlohmann_json CONFIG REQUIRED)
find_package(spdlog CONFIG REQUIRED)
find_package(cpr CONFIG REQUIRED)
find_package(unofficial-nayuki-qr-code-generator CONFIG REQUIRED)
# find_package(sioclient CONFIG REQUIRED)

# Source files
set(SOURCES
    src/main.cpp
    src/Application.cpp
    src/core/Config.cpp
    src/core/CryptoManager.cpp
    src/network/SocketIOClient.cpp
    src/network/HttpClient.cpp
    src/platform/ClipboardManager.cpp
    src/platform/HotkeyManager.cpp
    src/ui/MainWindow.cpp
    src/ui/SettingsWindow.cpp
    src/ui/TrayIcon.cpp
    src/ui/widgets/HotkeyRecorderEdit.cpp
    src/ui/widgets/QRCodeWidget.cpp
)

set(HEADERS
    src/Application.h
    src/core/Config.h
    src/core/CryptoManager.h
    src/core/Logger.h
    src/network/SocketIOClient.h
    src/network/HttpClient.h
    src/platform/ClipboardManager.h
    src/platform/HotkeyManager.h
    src/ui/MainWindow.h
    src/ui/SettingsWindow.h
    src/ui/TrayIcon.h
    src/ui/widgets/HotkeyRecorderEdit.h
    src/ui/widgets/QRCodeWidget.h
)

# Resources
set(RESOURCES
    resources/resources.qrc
)

# Create executable
add_executable(${PROJECT_NAME} WIN32
    ${SOURCES}
    ${HEADERS}
    ${RESOURCES}
)

# Link libraries
target_link_libraries(${PROJECT_NAME} PRIVATE
    Qt6::Core
    Qt6::Gui
    Qt6::Widgets
    Qt6::Network
    Qt6::WebSockets
    OpenSSL::SSL
    OpenSSL::Crypto
    nlohmann_json::nlohmann_json
    spdlog::spdlog
    cpr::cpr
    unofficial::nayuki-qr-code-generator::nayuki-qr-code-generator
    # sioclient::sioclient
    # sioclient::sioclient_tls
)

# Include directories
target_include_directories(${PROJECT_NAME} PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/src
)

# Windows-specific settings
if(WIN32)
    target_compile_definitions(${PROJECT_NAME} PRIVATE
        WIN32_LEAN_AND_MEAN
        NOMINMAX
        _CRT_SECURE_NO_WARNINGS
    )
endif()

# Copy Qt DLLs on Windows (for development)
if(WIN32)
    add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
            $<TARGET_FILE:Qt6::Core>
            $<TARGET_FILE:Qt6::Gui>
            $<TARGET_FILE:Qt6::Widgets>
            $<TARGET_FILE:Qt6::Network>
            $<TARGET_FILE_DIR:${PROJECT_NAME}>
    )
endif()
