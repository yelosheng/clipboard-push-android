@echo off
echo ========================================
echo  Clipboard Man - Start Web Client
echo ========================================
echo.

:: 检查 index.html 是否存在
if not exist "d:\android-dev\clipboard-man\client\index.html" (
    echo Error: index.html not found!
    pause
    exit /b 1
)

echo Opening web client in default browser...
start "" "d:\android-dev\clipboard-man\client\index.html"

echo.
echo ✓ Web client opened!
echo.
echo Make sure the server is running:
echo   cd d:\android-dev\clipboard-man\server
echo   python app.py
echo.
pause
