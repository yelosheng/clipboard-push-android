cmake_minimum_required(VERSION 3.20)
project(clipboard-man-cpp)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_definitions(-DUNICODE -D_UNICODE)

# Dependencies

# Dependencies

# Dependencies
set(OPENSSL_ROOT_DIR "${CMAKE_BINARY_DIR}/vcpkg_installed/x64-windows")
find_package(cpr CONFIG REQUIRED)
find_package(nlohmann_json CONFIG REQUIRED)
find_package(spdlog CONFIG REQUIRED)
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
if(NOT ASIO_INCLUDE_DIR)
    message(FATAL_ERROR "asio not found!")
endif()

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
file(GLOB_RECURSE SOURCES "src/*.cpp")
file(GLOB_RECURSE HEADERS "include/*.hpp" "include/*.h")

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
    spdlog::spdlog
    sioclient
    OpenSSL::SSL
    OpenSSL::Crypto
    user32 kernel32 gdi32 shell32 gdiplus shlwapi ole32 ws2_32 crypt32 bcrypt comctl32 comdlg32 advapi32
)



# Copy Config
add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
    "${CMAKE_SOURCE_DIR}/../client2/config.json"
    $<TARGET_FILE_DIR:${PROJECT_NAME}>
)
