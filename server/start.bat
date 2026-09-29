@echo off
chcp 65001 >nul
cd /d "%~dp0"

set PY=C:\Users\oldboy-pen\.workbuddy\binaries\python\envs\default\Scripts\python.exe

if not exist "%PY%" (
    echo [ERROR] Python not found:
    echo   %PY%
    echo Fix the PY path in this .bat file.
    pause
    exit /b 1
)

echo Starting server... (Ctrl+C to stop)
"%PY%" app.py

pause
