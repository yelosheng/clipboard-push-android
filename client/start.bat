@echo off
chcp 65001 >nul
echo Starting Clipboard Man Web Client...
echo.
echo Web Client: http://localhost:8080
echo.
python -m http.server 8080
pause
