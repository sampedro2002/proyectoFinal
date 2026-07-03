@echo off
title ControlEatFood - Setup de Entorno
chcp 65001 > nul

:: Obtener el carácter de Escape (ESC) de forma dinámica para colores ANSI
for /F "delims=" %%A in ('echo prompt $E ^| cmd') do set "ESC=%%A"
set "GREEN=%ESC%[92m"
set "YELLOW=%ESC%[93m"
set "RED=%ESC%[91m"
set "CYAN=%ESC%[96m"
set "RESET=%ESC%[0m"

echo =====================================================================
echo    %CYAN%VERIFICADOR Y CONFIGURADOR DE ENTORNO - CONTROL EAT FOOD%RESET%
echo =====================================================================
echo.

:: --- CONFIGURACIÓN DE BASE DE DATOS ---
:: NOTA: Se usa 'control_eat_food' para coincidir con la configuración del backend.
:: Si deseas usar 'control_food_eat', recuerda actualizar también el archivo 'application.yml' del backend.
set DB_NAME=control_eat_food
set DB_USER=admin
set DB_PASS=BN2002sg

:: --- 1. VERIFICAR JAVA 21 ---
echo [%YELLOW%1/3%RESET%] Verificando Java 21...
java -version 2>&1 | findstr "21." > nul
if %errorlevel% equ 0 (
    echo    %GREEN%[OK] Java 21 ya está instalado.%RESET%
) else (
    echo    %RED%[AVISO] Java 21 no está instalado o no se encuentra en el PATH.%RESET%
    echo    Intentando instalar JDK 21 automáticamente vía Winget...
    winget install EclipseAdoptium.Temurin.21.JDK --accept-package-agreements --accept-source-agreements
    if %errorlevel% equ 0 (
        echo    %GREEN%[OK] JDK 21 instalado con éxito. Recuerda reiniciar la consola al finalizar.%RESET%
    ) else (
        echo    %RED%[FALLÓ] No se pudo instalar JDK 21 automáticamente.%RESET%
        echo    Abriendo enlace para descarga e instalación manual...
        start https://adoptium.net/temurin/releases/?version=21
        pause
    )
)
echo.

:: --- 2. VERIFICAR NPM (NODE.JS) ---
echo [%YELLOW%2/3%RESET%] Verificando Node.js y NPM...
where npm > nul 2>&1
if %errorlevel% equ 0 (
    echo    %GREEN%[OK] Node.js y NPM ya están instalados.%RESET%
) else (
    echo    %RED%[AVISO] Node.js / NPM no están instalados.%RESET%
    echo    Intentando instalar Node.js LTS automáticamente vía Winget...
    winget install OpenJS.NodeJS.LTS --accept-package-agreements --accept-source-agreements
    if %errorlevel% equ 0 (
        echo    %GREEN%[OK] Node.js LTS instalado con éxito. Recuerda reiniciar la consola al finalizar.%RESET%
    ) else (
        echo    %RED%[FALLÓ] No se pudo instalar Node.js automáticamente.%RESET%
        echo    Abriendo enlace para descarga manual...
        start https://nodejs.org/
        pause
    )
)
echo.

:: --- 3. VERIFICAR / CREAR BASE DE DATOS ---
echo [%YELLOW%3/3%RESET%] Verificando Base de Datos '%DB_NAME%'...

:: 3.1. Verificar si Docker está en ejecución
docker info > nul 2>&1
if %errorlevel% equ 0 (
    echo    %GREEN%[INFO] Docker está en ejecución. Configurando base de datos en Docker...%RESET%
    
    :: Verificar si ya existe el contenedor
    docker ps -a --format "{{.Names}}" | findstr /i "control-mysql" > nul
    if %errorlevel% equ 0 (
        echo    El contenedor 'control-mysql' ya existe. Iniciándolo...
        docker start control-mysql > nul
        echo    %GREEN%[OK] Contenedor 'control-mysql' iniciado y corriendo.%RESET%
    ) else (
        echo    Creando contenedor Docker MySQL 8.0 ('control-mysql')...
        docker run --name control-mysql -p 3306:3306 -e MYSQL_ROOT_PASSWORD=%DB_PASS% -e MYSQL_DATABASE=%DB_NAME% -e MYSQL_USER=%DB_USER% -e MYSQL_PASSWORD=%DB_PASS% -d mysql:8.0
        if %errorlevel% equ 0 (
            echo    %GREEN%[OK] Contenedor Docker creado e iniciado con éxito.%RESET%
            echo    Base de datos: %DB_NAME%
            echo    Usuario: %DB_USER% / Clave: %DB_PASS%
        ) else (
            echo    %RED%[FALLÓ] No se pudo crear el contenedor Docker MySQL.%RESET%
        )
    )
) else (
    echo    %YELLOW%[INFO] Docker no está corriendo o no está instalado. Intentando MySQL local...%RESET%
    
    :: 3.2. Verificar si MySQL local existe en el PATH
    where mysql > nul 2>&1
    if %errorlevel% equ 0 (
        echo    Se detectó MySQL local en el PATH.
        echo    Se intentará crear la base de datos '%DB_NAME%' y el usuario '%DB_USER%'...
        echo    (Por favor, ingresa la contraseña de tu usuario 'root' de MySQL local)
        set /p ROOT_PASS="Contraseña de 'root' en MySQL local: "
        
        :: Comando SQL para crear base de datos, usuario y otorgar permisos
        mysql -u root -p"%ROOT_PASS%" -e "CREATE DATABASE IF NOT EXISTS %DB_NAME% CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER IF NOT EXISTS '%DB_USER%'@'localhost' IDENTIFIED BY '%DB_PASS%'; GRANT ALL PRIVILEGES ON %DB_NAME%.* TO '%DB_USER%'@'localhost'; FLUSH PRIVILEGES;" > nul 2>&1
        
        if %errorlevel% equ 0 (
            echo    %GREEN%[OK] Base de datos y usuario creados localmente con éxito.%RESET%
        ) else (
            echo    %RED%[FALLÓ] No se pudo conectar a MySQL local (contraseña incorrecta o permisos insuficientes).%RESET%
            echo    Crea manualmente la base de datos '%DB_NAME%' y el usuario '%DB_USER%' con clave '%DB_PASS%'.
        )
    ) else (
        echo    %RED%[ADVERTENCIA] No se detectó Docker ni 'mysql' local en el PATH.%RESET%
        echo    Asegúrate de que tu servidor MySQL esté encendido en el puerto 3306 y crea:
        echo    - Base de datos: %DB_NAME%
        echo    - Usuario: %DB_USER%
        echo    - Contraseña: %DB_PASS%
    )
)

echo.
echo =====================================================================
echo    %GREEN%¡PROCESO DE SETUP FINALIZADO!%RESET%
echo    Si el script instaló Java o Node.js, debes CERRAR esta consola
echo    y abrir una nueva para que los cambios del PATH surtan efecto.
echo =====================================================================
pause
