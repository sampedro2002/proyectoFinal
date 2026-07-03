#!/bin/bash

# -----------------------------------------------------------
# ControlEatFood - Setup de Entorno (Linux)
# -----------------------------------------------------------

set -e

DB_NAME="control_eat_food"
DB_USER="admin"
DB_PASS="BN2002sg"

# --- Colores ANSI ---
GREEN='\033[92m'
YELLOW='\033[93m'
RED='\033[91m'
CYAN='\033[96m'
RESET='\033[0m'

detect_pkg_manager() {
    if command -v apt &> /dev/null; then
        echo "apt"
    elif command -v dnf &> /dev/null; then
        echo "dnf"
    elif command -v yum &> /dev/null; then
        echo "yum"
    elif command -v pacman &> /dev/null; then
        echo "pacman"
    elif command -v zypper &> /dev/null; then
        echo "zypper"
    else
        echo "desconocido"
    fi
}

install_java21() {
    local pkg=$(detect_pkg_manager)
    echo "    Intentando instalar JDK 21 automáticamente..."
    case "$pkg" in
        apt)
            sudo apt update -y && sudo apt install -y openjdk-21-jdk
            ;;
        dnf|yum)
            sudo $pkg install -y java-21-openjdk-devel
            ;;
        pacman)
            sudo pacman -S --noconfirm jdk21-openjdk
            ;;
        zypper)
            sudo zypper install -y java-21-openjdk-devel
            ;;
        *)
            echo -e "    ${RED}[FALLÓ] No se reconoce el gestor de paquetes del sistema.${RESET}"
            echo "    Abriendo enlace para descarga manual..."
            xdg-open "https://adoptium.net/temurin/releases/?version=21" &> /dev/null || true
            read -rp "    Presiona ENTER para continuar..."
            return 1
            ;;
    esac
    if [ $? -eq 0 ]; then
        echo -e "    ${GREEN}[OK] JDK 21 instalado con éxito. Ejecuta 'source ~/.bashrc' o reinicia la terminal si hay cambios de PATH.${RESET}"
    else
        echo -e "    ${RED}[FALLÓ] No se pudo instalar JDK 21 automáticamente.${RESET}"
        echo "    Abriendo enlace para descarga manual..."
        xdg-open "https://adoptium.net/temurin/releases/?version=21" &> /dev/null || true
        read -rp "    Presiona ENTER para continuar..."
    fi
}

install_nodejs() {
    local pkg=$(detect_pkg_manager)
    echo "    Intentando instalar Node.js LTS automáticamente..."
    case "$pkg" in
        apt)
            curl -fsSL https://deb.nodesource.com/setup_lts.x | sudo -E bash -
            sudo apt install -y nodejs
            ;;
        dnf|yum)
            curl -fsSL https://rpm.nodesource.com/setup_lts.x | sudo -E bash -
            sudo $pkg install -y nodejs
            ;;
        pacman)
            sudo pacman -S --noconfirm nodejs npm
            ;;
        zypper)
            sudo zypper install -y nodejs npm
            ;;
        *)
            echo -e "    ${RED}[FALLÓ] No se reconoce el gestor de paquetes del sistema.${RESET}"
            echo "    Abriendo enlace para descarga manual..."
            xdg-open "https://nodejs.org/" &> /dev/null || true
            read -rp "    Presiona ENTER para continuar..."
            return 1
            ;;
    esac
    if [ $? -eq 0 ]; then
        echo -e "    ${GREEN}[OK] Node.js LTS instalado con éxito.${RESET}"
    else
        echo -e "    ${RED}[FALLÓ] No se pudo instalar Node.js automáticamente.${RESET}"
        echo "    Abriendo enlace para descarga manual..."
        xdg-open "https://nodejs.org/" &> /dev/null || true
        read -rp "    Presiona ENTER para continuar..."
    fi
}

# -----------------------------------------------------------
echo "====================================================================="
echo -e "   ${CYAN}VERIFICADOR Y CONFIGURADOR DE ENTORNO - CONTROL EAT FOOD${RESET}"
echo "====================================================================="
echo ""

# --- 1. VERIFICAR JAVA 21 ---
echo -e "[${YELLOW}1/3${RESET}] Verificando Java 21..."
if java -version 2>&1 | grep -q "21\."; then
    echo -e "    ${GREEN}[OK] Java 21 ya está instalado.${RESET}"
else
    echo -e "    ${RED}[AVISO] Java 21 no está instalado o no se encuentra en el PATH.${RESET}"
    install_java21
fi
echo ""

# --- 2. VERIFICAR NPM (NODE.JS) ---
echo -e "[${YELLOW}2/3${RESET}] Verificando Node.js y NPM..."
if command -v npm &> /dev/null; then
    echo -e "    ${GREEN}[OK] Node.js y NPM ya están instalados.${RESET}"
else
    echo -e "    ${RED}[AVISO] Node.js / NPM no están instalados.${RESET}"
    install_nodejs
fi
echo ""

# --- 3. VERIFICAR / CREAR BASE DE DATOS ---
echo -e "[${YELLOW}3/3${RESET}] Verificando Base de Datos '$DB_NAME'..."

# 3.1. Docker
if docker info &> /dev/null; then
    echo -e "    ${GREEN}[INFO] Docker está en ejecución. Configurando base de datos en Docker...${RESET}"

    if docker ps -a --format '{{.Names}}' | grep -qi "control-mysql"; then
        echo "    El contenedor 'control-mysql' ya existe. Iniciándolo..."
        docker start control-mysql &> /dev/null
        echo -e "    ${GREEN}[OK] Contenedor 'control-mysql' iniciado y corriendo.${RESET}"
    else
        echo "    Creando contenedor Docker MySQL 8.0 ('control-mysql')..."
        docker run --name control-mysql \
            -p 3306:3306 \
            -e MYSQL_ROOT_PASSWORD="$DB_PASS" \
            -e MYSQL_DATABASE="$DB_NAME" \
            -e MYSQL_USER="$DB_USER" \
            -e MYSQL_PASSWORD="$DB_PASS" \
            -d mysql:8.0
        if [ $? -eq 0 ]; then
            echo -e "    ${GREEN}[OK] Contenedor Docker creado e iniciado con éxito.${RESET}"
            echo "    Base de datos: $DB_NAME"
            echo "    Usuario: $DB_USER / Clave: $DB_PASS"
        else
            echo -e "    ${RED}[FALLÓ] No se pudo crear el contenedor Docker MySQL.${RESET}"
        fi
    fi
else
    echo -e "    ${YELLOW}[INFO] Docker no está corriendo o no está instalado. Intentando MySQL local...${RESET}"

    # 3.2. MySQL local
    if command -v mysql &> /dev/null; then
        echo "    Se detectó MySQL local en el PATH."
        echo "    Se intentará crear la base de datos '$DB_NAME' y el usuario '$DB_USER'..."
        echo "    (Por favor, ingresa la contraseña de tu usuario 'root' de MySQL local)"
        read -rsp "    Contraseña de 'root' en MySQL local: " ROOT_PASS
        echo ""

        mysql -u root -p"$ROOT_PASS" -e "
            CREATE DATABASE IF NOT EXISTS $DB_NAME CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
            CREATE USER IF NOT EXISTS '$DB_USER'@'localhost' IDENTIFIED BY '$DB_PASS';
            GRANT ALL PRIVILEGES ON $DB_NAME.* TO '$DB_USER'@'localhost';
            FLUSH PRIVILEGES;
        " 2> /dev/null

        if [ $? -eq 0 ]; then
            echo -e "    ${GREEN}[OK] Base de datos y usuario creados localmente con éxito.${RESET}"
        else
            echo -e "    ${RED}[FALLÓ] No se pudo conectar a MySQL local (contraseña incorrecta o permisos insuficientes).${RESET}"
            echo "    Crea manualmente la base de datos '$DB_NAME' y el usuario '$DB_USER' con clave '$DB_PASS'."
        fi
    else
        echo -e "    ${RED}[ADVERTENCIA] No se detectó Docker ni 'mysql' local en el PATH.${RESET}"
        echo "    Asegúrate de que tu servidor MySQL esté encendido en el puerto 3306 y crea:"
        echo "    - Base de datos: $DB_NAME"
        echo "    - Usuario: $DB_USER"
        echo "    - Contraseña: $DB_PASS"
    fi
fi

echo ""
echo "====================================================================="
echo -e "   ${GREEN}¡PROCESO DE SETUP FINALIZADO!${RESET}"
echo "   Si el script instaló Java o Node.js, ejecuta:"
echo "       source ~/.bashrc"
echo "   o reinicia la terminal para que los cambios del PATH surtan efecto."
echo "====================================================================="
