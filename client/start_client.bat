@echo off
chcp 65001 >nul
title Clipboard Man Client

echo ================================================
echo Clipboard Man Client
echo ================================================
echo.

cd /d "%~dp0"

echo 正在启动客户端...
echo 服务器地址: 请检查 config.json
echo.

start "Clipboard Man Client - Minimized to SysTray" /min cmd /c "python clipboard_man_client.py"

if errorlevel 1 (
    echo.
    echo [错误] 启动失败！
    echo 请检查：
    echo   1. Python 是否已安装
    echo   2. 依赖包是否已安装 (pip install -r requirements.txt^)
    echo   3. 配置文件 config.json 是否正确
    echo.
    pause
)
