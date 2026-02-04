cmake_minimum_required(VERSION 3.20)
project(clipboard-push)

set(CMAKE_CXX_STANDARD 20)

# 如果你还没有安装 vcpkg，需要先设置 VCPKG_ROOT 环境变量或使用工具链文件
# set(CMAKE_TOOLCHAIN_FILE "$ENV{VCPKG_ROOT}/scripts/buildsystems/vcpkg.cmake")

find_package(cpr CONFIG REQUIRED)
find_package(nlohmann_json CONFIG REQUIRED)
find_package(socket.io-client-cpp CONFIG REQUIRED)
find_package(spdlog CONFIG REQUIRED)

add_executable(clipboard-push
    src/main.cpp
    src/Clipboard.cpp
    src/Clipboard.hpp
    src/Network.cpp
    src/Network.hpp
    src/Config.hpp
)

# 链接依赖库
target_link_libraries(clipboard-push PRIVATE 
    cpr::cpr 
    nlohmann_json::nlohmann_json
    socket.io-client-cpp::socket.io-client-cpp
    spdlog::spdlog
)

# 链接 Windows 系统库
target_link_libraries(clipboard-push PRIVATE 
    User32.lib
    Kernel32.lib
    Gdi32.lib
    Shell32.lib
    Gdiplus.lib
    Shlwapi.lib
)

# 复制配置文件到输出目录
file(COPY "${CMAKE_SOURCE_DIR}/../config.json" DESTINATION "${CMAKE_BINARY_DIR}")
