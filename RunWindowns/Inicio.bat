@echo off
setlocal EnableExtensions
title Control Eat Food - Instalador

rem ---------------------------------------------------------------------------
rem  Lanzador de Inicio.ps1. Funciona igual en Windows 10 y Windows 11.
rem
rem  Que hace, en orden:
rem    1. Se situa en su propia carpeta (funciona con doble clic desde
rem       cualquier sitio, incluida una ruta de red).
rem    2. Comprueba que Inicio.ps1 este al lado.
rem    3. Si ya se ejecuta como Administrador, lanza el script directamente
rem       (antes se re-elevaba siempre y abria una segunda ventana de sobra).
rem    4. Si no, pide elevacion por UAC. Si el usuario la cancela, NO se cierra
rem       en silencio: avisa y abre igualmente en modo limitado, donde la
rem       opcion [1] Pruebas si funciona.
rem ---------------------------------------------------------------------------

cd /d "%~dp0"

set "PS1=%~dp0Inicio.ps1"
set "PSEXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%PSEXE%" set "PSEXE=powershell.exe"

if not exist "%PS1%" (
    echo.
    echo   [X] No se encontro Inicio.ps1 junto a este archivo.
    echo       Ruta buscada: "%PS1%"
    echo.
    echo   Inicio.bat e Inicio.ps1 deben estar en la MISMA carpeta.
    echo.
    pause
    endlocal
    exit /b 1
)

rem Deteccion de privilegios: "fltmc" solo responde OK elevado y esta presente
rem en todas las ediciones de Windows 10/11. "net session" se usa de respaldo
rem por si el servicio de filtros no esta disponible.
fltmc >nul 2>&1
if not errorlevel 1 goto :run
net session >nul 2>&1
if not errorlevel 1 goto :run

rem Sin privilegios: se pide UAC. La ruta viaja por variable de entorno y se
rem entrecomilla dentro de PowerShell, de modo que funciona aunque la carpeta
rem del proyecto tenga ESPACIOS o apostrofes (el lanzador anterior fallaba ahi).
set "CEF_PS1=%PS1%"
set "CEF_PSEXE=%PSEXE%"
"%PSEXE%" -NoProfile -ExecutionPolicy Bypass -Command "try { Start-Process -FilePath $env:CEF_PSEXE -Verb RunAs -Wait -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',('\"' + $env:CEF_PS1 + '\"')); exit 0 } catch { exit 1 }"
if not errorlevel 1 goto :end

echo.
echo   [!] No se pudo elevar a Administrador (UAC cancelado o bloqueado por
echo       politicas del equipo).
echo.
echo       Se abrira SIN permisos de Administrador. En ese modo solo funciona
echo       la opcion [1] Pruebas; para Produccion, cierra esta ventana y haz
echo       click derecho sobre Inicio.bat - "Ejecutar como administrador".
echo.
pause

:run
"%PSEXE%" -NoProfile -ExecutionPolicy Bypass -File "%PS1%"
if errorlevel 1 (
    echo.
    echo   [X] El instalador termino con errores.
    echo       Revisa el log mas reciente en: "%~dp0logs"
    echo.
    pause
)

:end
endlocal
exit /b 0
