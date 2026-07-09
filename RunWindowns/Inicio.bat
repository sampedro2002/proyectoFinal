@echo off
setlocal
chcp 65001 > nul
title ControlEatFood - Gestor Principal

:: Obtener el carácter de Escape (ESC) de forma dinámica para colores ANSI
for /F "delims=" %%A in ('echo prompt $E ^| cmd') do set "ESC=%%A"
set "GREEN=%ESC%[92m"
set "YELLOW=%ESC%[93m"
set "CYAN=%ESC%[96m"
set "RESET=%ESC%[0m"

:menu
cls
echo =====================================================================
echo    %CYAN%GESTOR PRINCIPAL - CONTROL EAT FOOD%RESET%
echo =====================================================================
echo.
echo Seleccione la accion que desea realizar:
echo.
echo    %YELLOW%[1]%RESET% Preparar Entorno Local (Modo Desarrollo)
echo        - Instala dependencias (Java, Node, MySQL) y configura el
echo          proyecto para que puedas ejecutarlo y modificarlo.
echo.
echo    %YELLOW%[2]%RESET% Instalar Servicio (Modo Produccion)
echo        - Compila y configura la aplicacion para ejecutarse
echo          permanentemente en segundo plano (requiere Administrador).
echo.
echo    %YELLOW%[3]%RESET% Desinstalar Servicio (Modo Produccion)
echo        - Detiene y elimina el servicio de Windows de esta aplicacion.
echo.
echo    %YELLOW%[4]%RESET% Salir
echo.
set /p opcion="Ingrese el numero de la opcion (1-4): "

if "%opcion%"=="1" goto setup
if "%opcion%"=="2" goto install
if "%opcion%"=="3" goto uninstall
if "%opcion%"=="4" exit /b

goto menu

:setup
cls
call "%~dp0setup_env.bat"
echo.
echo %GREEN%Retornando al menu principal...%RESET%
pause
goto menu

:install
cls
echo Iniciando instalacion de produccion...
echo %YELLOW%Se abrira una nueva ventana solicitando permisos de Administrador.%RESET%
echo Por favor, continua la instalacion en esa ventana.
powershell -NoProfile -Command "Start-Process -FilePath 'powershell' -ArgumentList '-NoProfile -ExecutionPolicy Bypass -File \"%~dp0install.ps1\"' -Verb RunAs"
echo.
pause
goto menu

:uninstall
cls
echo Iniciando desinstalacion...
echo %YELLOW%Se abrira una nueva ventana solicitando permisos de Administrador.%RESET%
echo Por favor, continua el proceso en esa ventana.
powershell -NoProfile -Command "Start-Process -FilePath 'powershell' -ArgumentList '-NoProfile -ExecutionPolicy Bypass -File \"%~dp0uninstall.ps1\"' -Verb RunAs"
echo.
pause
goto menu
