cmake_minimum_required(VERSION 3.20)
project(clipboard-push)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(cpr CONFIG REQUIRED)
find_package(nlohmann_json CONFIG REQUIRED)
find_package(spdlog CONFIG REQUIRED)
find_package(ixwebsocket CONFIG REQUIRED)

add_executable(clipboard-push
    src/main.cpp
    src/Clipboard.cpp
    src/Clipboard.hpp
    src/Network.cpp
    src/Network.hpp
    src/Config.hpp
)

target_link_libraries(clipboard-push PRIVATE 
    cpr::cpr 
    nlohmann_json::nlohmann_json
    spdlog::spdlog
    ixwebsocket::ixwebsocket
)

target_link_libraries(clipboard-push PRIVATE 
    user32 kernel32 gdi32 shell32 gdiplus shlwapi ole32 ws2_32 crypt32 bcrypt
)

file(COPY "${CMAKE_SOURCE_DIR}/../config.json" DESTINATION "${CMAKE_BINARY_DIR}")
