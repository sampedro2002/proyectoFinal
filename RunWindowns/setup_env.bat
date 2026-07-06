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
:: Esquema actual (V7): Sin tabla 'position', cargo como texto en employee.position_title.
:: Campos nuevos en employee: public_code (EMP-XXXXXX), observation, position_title.
:: Roles disponibles: ADMIN, CATERING (SUPERVISOR eliminado en V7).
:: Usuarios catering: cateringNorte, cateringCentro, cateringSur (renombrados en V6).
set DB_NAME=control_eat_food
set DB_USER=admin
set DB_PASS=BN2002sg

:: --- VARIABLES DE ENTORNO DEL BACKEND ---
:: Configuración adicional para el backend Spring Boot (application.yml).
:: Descomenta y ajusta según tu entorno de despliegue.
set DB_URL=jdbc:mysql://localhost:3306/%DB_NAME%?createDatabaseIfNotExist=true^&serverTimezone=America/Guayaquil
set JWT_SECRET=Y29udHJvbC1lYXQtZm9vZC1zZWNyZXQta2V5LWNoYW5nZS1pbi1wcm9kdWN0aW9uLTEyMzQ1Ng==
set PUBLIC_URL=
set CORS_ORIGINS=http://localhost:5173,http://localhost:5174,http://localhost:4173
set RATE_LIMIT_ENABLED=true
set RATE_LIMIT_AUTH=10
set RATE_LIMIT_SCAN=60
set BIOMETRIC_ENCRYPTION_KEY=
set ZK_NATIVE_PATH=./native

:: --- 1. VERIFICAR JAVA 21 ---
echo [%YELLOW%1/5%RESET%] Verificando Java 21...
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
echo [%YELLOW%2/5%RESET%] Verificando Node.js y NPM...
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
echo [%YELLOW%3/5%RESET%] Verificando Base de Datos '%DB_NAME%'...

:: 3.1. Detectar si el puerto 3306 ya está en uso (MySQL local o contenedor ya corriendo)
netstat -ano | find "LISTENING" | findstr ":3306" > nul
if %errorlevel% neq 0 goto check_docker

echo    %GREEN%[INFO] Se detectó un servicio en el puerto 3306 (MySQL o similar ya está corriendo).%RESET%
:: Verificar si es Docker
docker ps --format "{{.Names}}" | findstr /i "control-mysql" > nul 2>&1
if %errorlevel% equ 0 (
    echo    %GREEN%[OK] El contenedor Docker 'control-mysql' está en ejecución.%RESET%
) else (
    echo    %YELLOW%[INFO] Asumiendo que MySQL está corriendo de forma local.%RESET%
    echo    Asegúrate de que la base de datos '%DB_NAME%' y el usuario existan.
)
goto db_setup_done

:check_docker
echo    %YELLOW%[INFO] No se detectó servicio en el puerto 3306. Buscando en Docker...%RESET%

docker info > nul 2>&1
if %errorlevel% neq 0 goto check_local_mysql

echo    %GREEN%[INFO] Docker está en ejecución.%RESET%
docker ps -a --format "{{.Names}}" | findstr /i "control-mysql" > nul
if %errorlevel% neq 0 goto create_container

echo    El contenedor 'control-mysql' ya existe. Iniciándolo...
docker start control-mysql > nul
echo    %GREEN%[OK] Contenedor 'control-mysql' iniciado y corriendo.%RESET%
goto db_setup_done

:create_container
echo    Creando contenedor Docker MySQL 8.0 ('control-mysql')...
docker run --name control-mysql -p 3306:3306 -e MYSQL_ROOT_PASSWORD=%DB_PASS% -e MYSQL_DATABASE=%DB_NAME% -e MYSQL_USER=%DB_USER% -e MYSQL_PASSWORD=%DB_PASS% -d mysql:8.0
if %errorlevel% equ 0 (
    echo    %GREEN%[OK] Contenedor Docker creado e iniciado con éxito.%RESET%
) else (
    echo    %RED%[FALLÓ] No se pudo crear el contenedor Docker MySQL.%RESET%
)
goto db_setup_done

:check_local_mysql
echo    %RED%[AVISO] Docker no está en ejecución o no está instalado.%RESET%
echo    %YELLOW%[INFO] Intentando configurar MySQL local...%RESET%

where mysql > nul 2>&1
if %errorlevel% neq 0 goto manual_setup

echo    Se detectó 'mysql' en el PATH.
set "ROOT_PASS="
set /p ROOT_PASS="Contraseña de 'root' en MySQL local (o presiona ENTER para omitir): "
if "%ROOT_PASS%"=="" goto db_setup_done

mysql -u root -p"%ROOT_PASS%" -e "CREATE DATABASE IF NOT EXISTS %DB_NAME% CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER IF NOT EXISTS '%DB_USER%'@'localhost' IDENTIFIED BY '%DB_PASS%'; GRANT ALL PRIVILEGES ON %DB_NAME%.* TO '%DB_USER%'@'localhost'; FLUSH PRIVILEGES;" > nul 2>&1
if %errorlevel% equ 0 (
    echo    %GREEN%[OK] Base de datos y usuario configurados localmente.%RESET%
) else (
    echo    %RED%[FALLÓ] No se pudo configurar MySQL local (verifica credenciales/permisos).%RESET%
)
goto db_setup_done

:manual_setup
echo    %RED%[ADVERTENCIA] No se detectó Docker ni 'mysql' local. Crea la BD manualmente.%RESET%
echo    - Base de datos: %DB_NAME%
echo    - Usuario: %DB_USER%
echo    - Contraseña: %DB_PASS%

:db_setup_done

echo.
:: --- 4. INSTALAR DEPENDENCIAS DEL FRONTEND ---
echo [%YELLOW%4/5%RESET%] Instalando dependencias del Frontend...
if exist "%~dp0..\controlEatFoodWeb\frontend\package.json" (
    pushd "%~dp0..\controlEatFoodWeb\frontend"
    if exist "node_modules" (
        echo    %GREEN%[OK] Las dependencias ya estan instaladas (node_modules existe).%RESET%
    ) else (
        echo    %YELLOW%[INFO] Instalando dependencias de Node.js, esto puede tardar un momento...%RESET%
        call npm install
        echo    %GREEN%[OK] Dependencias instaladas con exito.%RESET%
    )
    if not exist "node_modules\qrcode" (
        echo    %YELLOW%[INFO] Instalando dependencia adicional: qrcode...%RESET%
        call npm install qrcode
        echo    %GREEN%[OK] qrcode instalado con exito.%RESET%
    ) else (
        echo    %GREEN%[OK] qrcode ya esta instalado.%RESET%
    )
    popd
) else (
    echo    %RED%[AVISO] No se encontro el proyecto frontend en la ruta esperada.%RESET%
)

echo.
:: --- 5. EJECUTAR SETUP.EXE ---
echo [%YELLOW%5/5%RESET%] Verificando ejecucion de Setup.exe...
if exist "%~dp0.setup_completado.lock" (
    echo    %GREEN%[OK] El archivo setup.exe ya fue ejecutado previamente.%RESET%
) else (
    echo    %YELLOW%[INFO] Ejecutando setup.exe, por favor completa la instalacion en la ventana que aparecera...%RESET%
    start /wait "" "%~dp0setup.exe"
    echo OK > "%~dp0.setup_completado.lock"
    echo    %GREEN%[OK] Proceso de setup.exe finalizado.%RESET%
)

echo.
echo =====================================================================
echo    %GREEN%¡PROCESO DE SETUP FINALIZADO!%RESET%
echo    Si el script instaló Java o Node.js, debes CERRAR esta consola
echo    y abrir una nueva para que los cambios del PATH surtan efecto.
echo.
echo    %YELLOW%Para iniciar el backend:%RESET%
echo      cd controlEatFoodWeb\backend
echo      mvn spring-boot:run
echo.
echo    %YELLOW%Para iniciar el frontend:%RESET%
echo      cd controlEatFoodWeb\frontend
echo      npm run dev
echo.
echo    %CYAN%URL de conexión a BD (IntelliJ/phpMyAdmin):%RESET%
echo    jdbc:mysql://localhost:3306/%DB_NAME%?serverTimezone=America/Guayaquil
echo    Usuario: %DB_USER% | Password: %DB_PASS%
echo =====================================================================
pause
