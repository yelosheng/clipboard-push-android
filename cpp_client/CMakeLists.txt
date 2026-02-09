cmake_minimum_required(VERSION 3.20)
project(clipboard-man-cpp)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_definitions(-DUNICODE -D_UNICODE -DWIN32_LEAN_AND_MEAN -DNOMINMAX)

if(MSVC)
    add_compile_options(/utf-8)
endif()

# Dependencies

# Dependencies

# Dependencies
set(OPENSSL_ROOT_DIR "${CMAKE_BINARY_DIR}/vcpkg_installed/x64-windows")
find_package(cpr CONFIG REQUIRED)
find_package(nlohmann_json CONFIG REQUIRED)
find_package(spdlog CONFIG REQUIRED)
find_package(fmt CONFIG REQUIRED)
find_package(OpenSSL REQUIRED)

# Header-only libs (Manual find)
find_path(WEBSOCKETPP_INCLUDE_DIR websocketpp/version.hpp)
find_path(RAPIDJSON_INCLUDE_DIR rapidjson/rapidjson.h)
find_path(ASIO_INCLUDE_DIR asio.hpp)

if(NOT WEBSOCKETPP_INCLUDE_DIR)
    message(FATAL_ERROR "websocketpp not found!")
endif()
if(NOT RAPIDJSON_INCLUDE_DIR)
    message(FATAL_ERROR "rapidjson not found!")
endif()

# Manual find for libqrencode
find_path(QRENCODE_INCLUDE_DIR qrencode.h)
find_library(QRENCODE_LIBRARY_RELEASE NAMES qrencode libqrencode)
find_library(QRENCODE_LIBRARY_DEBUG NAMES qrencode d libqrencode d qrencoded libqrencoded)

if(NOT QRENCODE_LIBRARY_RELEASE)
    set(QRENCODE_LIBRARY_RELEASE ${QRENCODE_LIBRARY_DEBUG})
endif()

if(NOT QRENCODE_INCLUDE_DIR OR NOT QRENCODE_LIBRARY_RELEASE)
    message(FATAL_ERROR "libqrencode not found!")
endif()

include_directories(${QRENCODE_INCLUDE_DIR})

include_directories(${WEBSOCKETPP_INCLUDE_DIR} ${RAPIDJSON_INCLUDE_DIR} ${ASIO_INCLUDE_DIR})


# Socket.IO Client (FetchContent)
include(FetchContent)
FetchContent_Declare(
    sioclient
    GIT_REPOSITORY https://github.com/socketio/socket.io-client-cpp.git
    GIT_TAG        3.1.0
)
set(BUILD_UNIT_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(sioclient)

# Source Files
set(SOURCES
    src/main.cpp
    src/Window.cpp
    src/MainWindow.cpp
    src/TrayIcon.cpp
    src/ClipboardManager.cpp
    src/ConfigManager.cpp
    src/CryptoManager.cpp
    src/HotkeyManager.cpp
    src/NetworkClient.cpp
    src/Base64.cpp
    src/app.manifest
)

set(HEADERS
    include/Common.hpp
    include/Window.hpp
    include/MainWindow.hpp
    include/TrayIcon.hpp
    include/ClipboardManager.hpp
    include/ConfigManager.hpp
    include/CryptoManager.hpp
    include/HotkeyManager.hpp
    include/NetworkClient.hpp
    include/Base64.hpp
)

# Executable (Win32 GUI app)
add_executable(${PROJECT_NAME} WIN32 ${SOURCES} ${HEADERS})

# Include Directories
# Include Directories
target_include_directories(${PROJECT_NAME} PRIVATE include)
target_include_directories(${PROJECT_NAME} PRIVATE src)
target_include_directories(${PROJECT_NAME} PRIVATE ${sioclient_SOURCE_DIR}/src)

# Linking
target_link_libraries(${PROJECT_NAME} PRIVATE 
    cpr::cpr
    nlohmann_json::nlohmann_json
    sioclient
    spdlog::spdlog
    fmt::fmt
    OpenSSL::SSL
    OpenSSL::Crypto
    ${QRENCODE_LIBRARY_RELEASE}
    user32 kernel32 gdi32 shell32 gdiplus shlwapi ole32 ws2_32 crypt32 bcrypt comctl32 comdlg32 advapi32 dhcpcsvc
)



# Copy Config
add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
    "${CMAKE_SOURCE_DIR}/../client2/config.json"
    $<TARGET_FILE_DIR:${PROJECT_NAME}>
)
