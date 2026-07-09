@echo off
setlocal
title ControlEatFood - Instalador de Produccion

:: El instalador real es install.ps1 (PowerShell). Este .bat solo evita el
:: problema de politica de ejecucion / doble clic y solicita privilegios
:: de administrador automaticamente (UAC), como requiere install.ps1.

net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Solicitando privilegios de administrador...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1"
pause
