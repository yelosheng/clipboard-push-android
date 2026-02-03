@echo off
chcp 65001 >nul
echo ========================================
echo   Clipboard Man Server
echo ========================================
echo.

cd /d "%~dp0"

REM 检查虚拟环境
if exist "venv\Scripts\activate.bat" (
    echo [*] 激活虚拟环境...
    call venv\Scripts\activate.bat
) else (
    echo [!] 未找到虚拟环境，使用系统 Python
)

echo [*] 启动服务器...
python app.py

pause
