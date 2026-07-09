@echo off
setlocal
title ControlEatFood - Desinstalador

net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Solicitando privilegios de administrador...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0uninstall.ps1"
pause
