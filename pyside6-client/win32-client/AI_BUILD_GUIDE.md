# Clipboard Push Win32: AI 编译与环境指南

**致后续处理此项目的 AI 助手：**
本项目是一个追求极致兼容性（Win10/11）和轻量化（<1MB）的原生 C++ 项目。由于当前开发机器环境特殊（同时存在 MinGW 和 MSVC），请务必严格遵守以下编译准则，否则会导致链接错误或运行闪退。

---

## 🛠️ 1. 核心编译环境 (关键路径)

在执行任何编译指令前，请先确认并使用以下路径：
- **MSVC 工具链初始化**：`C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat`
- **VCPKG 根目录**：`D:\vcpkg`
- **CMake 工具链文件**：`D:\vcpkg\scripts\buildsystems\vcpkg.cmake`

---

## 🚀 2. 终极原子编译指令 (必用模板)

由于系统环境变量中存在 MinGW，普通 `cmake ..` 会错误调用 GCC 导致崩溃。**必须**在同一个 Shell 会话中初始化 MSVC 环境并强制指定 vcpkg 静态三元组。

**请直接复制并执行此指令进行构建：**
```powershell
cmd /c "call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" && cd /d [项目所在绝对路径]\win32-client && rd /s /q build & mkdir build & cd build & cmake -G "Ninja" -DCMAKE_TOOLCHAIN_FILE=D:/vcpkg/scripts/buildsystems/vcpkg.cmake -DVCPKG_TARGET_TRIPLET=x64-windows-static -DCMAKE_BUILD_TYPE=Release .. && cmake --build ."
```

---

## ⚠️ 3. 必须遵守的四大禁令

1.  **禁动静态链接配置**：
    - 必须使用 `-DVCPKG_TARGET_TRIPLET=x64-windows-static`。
    - `CMakeLists.txt` 中必须保留 `/MT` (Static CRT) 选项。这是为了确保在没有安装 VC++ 运行库的 Win10 上不闪退。
2.  **严禁混用编译器**：
    - 看到 `D:\mingw64` 的报错时，绝对不要尝试去兼容它。必须通过 `vcvars64.bat` 强行切换回 **cl.exe (MSVC)**。
3.  **保持静默 GUI 模式**：
    - 入口函数必须是 `APIENTRY wWinMain`。
    - `add_executable` 必须带 `WIN32` 标志。严禁改回 `main()`，否则会导致程序运行时自带一个黑色的命令行窗口。
4.  **LOG_RAW 宏保护**：
    - 处理 Socket.IO 的 JSON 消息时，必须使用 `LOG_RAW` 宏而非 `LOG_DEBUG`。因为 JSON 中的 `%` 符号会触发格式化字符串漏洞导致程序瞬间崩溃。

---

## 🔄 4. 常见问题排查

- **编译成功但运行闪退**：检查是否误改为了动态链接 (`/MD`)。请检查生成的 `.exe` 大小，正常全静态版应在 **940KB - 960KB** 之间。
- **找不到头文件**：确保 CMake 命令行中带入了正确的 `CMAKE_TOOLCHAIN_FILE` 路径。
- **找不到 Ninja**：如果系统没有 Ninja，可以尝试删除 `-G "Ninja"` 让 CMake 使用默认生成器，但仍需保留 `vcvars64.bat` 环境。

---

**当前版本：v4.7.0 Stable**
**核心状态：Peer 感知已上线，Protocol 4.0 已打通。**
