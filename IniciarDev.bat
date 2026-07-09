@echo off
setlocal
title ControlEatFood - Dev

:: Arranca backend (Spring Boot) y frontend (Vite) en ventanas separadas.
:: Usa %~dp0 para ubicar las carpetas sin importar desde donde se ejecute el .bat.

echo Iniciando backend (Spring Boot) en una nueva ventana...
start "ControlEatFood - Backend" cmd /k "cd /d "%~dp0controlEatFoodWeb\backend" && mvn spring-boot:run"

echo Iniciando frontend (Vite) en una nueva ventana...
start "ControlEatFood - Frontend" cmd /k "cd /d "%~dp0controlEatFoodWeb\frontend" && npm run dev"

echo.
echo Backend y frontend lanzados en ventanas separadas.
echo   Backend:  http://localhost:3000
echo   Frontend: http://localhost:5173
echo.
