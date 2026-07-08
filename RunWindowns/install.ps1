#Requires -RunAsAdministrator
<#
.SYNOPSIS
    ControlEatFood - Instalador de Produccion para Windows Server
.DESCRIPTION
    Script interactivo para instalar/desplegar ControlEatFood en un
    Windows Server 10, con soporte para base de datos local o remota (Linux).
.NOTES
    Ejecutar como Administrador.
    PowerShell 5.1+ (incluido en Windows Server 2016+).
#>

[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ═══════════════════════════════════════════════════════════════════
# CONFIGURACION GLOBAL
# ═══════════════════════════════════════════════════════════════════
$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ScriptRoot
$ConfigDir = Join-Path $ScriptRoot "config"
$LogsDir = Join-Path $ScriptRoot "logs"
$LogFile = Join-Path $LogsDir ("install_{0}.log" -f (Get-Date -Format "yyyyMMdd_HHmmss"))
$ConfigFile = Join-Path $ConfigDir "install_config.json"
$BackendDir = Join-Path $ProjectRoot "controlEatFoodWeb\backend"
$FrontendDir = Join-Path $ProjectRoot "controlEatFoodWeb\frontend"
$ProdYmlPath = Join-Path $ConfigDir "application-prod.yml"
$NssmDir = Join-Path $ScriptRoot "tools"
$NssmExe = Join-Path $NssmDir "nssm.exe"
$ServiceName = "ControlEatFood"

# Asegurar directorios
@($ConfigDir, $LogsDir, $NssmDir) | ForEach-Object {
    if (-not (Test-Path $_)) { New-Item -ItemType Directory -Path $_ -Force | Out-Null }
}

# ═══════════════════════════════════════════════════════════════════
# FUNCIONES DE UTILIDAD
# ═══════════════════════════════════════════════════════════════════

function Write-Log {
    param([string]$Message, [string]$Level = 'INFO')
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $line = "[$timestamp] [$Level] $Message"
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
    switch ($Level) {
        'ERROR'   { Write-Host "  [X] $Message" -ForegroundColor Red }
        'WARN'    { Write-Host "  [!] $Message" -ForegroundColor Yellow }
        'SUCCESS' { Write-Host "  [OK] $Message" -ForegroundColor Green }
        'STEP'    { Write-Host "`n>> $Message" -ForegroundColor Cyan }
        default   { Write-Host "  $Message" }
    }
}

function Write-Banner {
    Write-Host ""
    Write-Host "======================================================================" -ForegroundColor Cyan
    Write-Host "     CONTROL EAT FOOD - Instalador de Produccion" -ForegroundColor Cyan
    Write-Host "     Windows Server | $(Get-Date -Format 'yyyy-MM-dd')" -ForegroundColor DarkCyan
    Write-Host "======================================================================" -ForegroundColor Cyan
    Write-Host ""
}

function Show-Menu {
    param([string]$Title, [string[]]$Options)
    Write-Host ""
    Write-Host "  --- $Title ---" -ForegroundColor Yellow
    Write-Host ""
    for ($i = 0; $i -lt $Options.Count; $i++) {
        Write-Host "    [$($i+1)] $($Options[$i])"
    }
    Write-Host ""
}

function Read-Choice {
    param([string]$Prompt, [int]$Min = 1, [int]$Max)
    do {
        $input = Read-Host "  $Prompt"
        if ($input -match '^\d+$' -and [int]$input -ge $Min -and [int]$input -le $Max) {
            return [int]$input
        }
        Write-Host "    Opcion no valida. Ingrese un numero entre $Min y $Max." -ForegroundColor Red
    } while ($true)
}

function Read-Default {
    param([string]$Prompt, [string]$Default)
    $input = Read-Host "  $Prompt [$Default]"
    if ([string]::IsNullOrWhiteSpace($input)) { return $Default }
    return $input.Trim()
}

function Read-SecureInput {
    param([string]$Prompt)
    $input = Read-Host "  $Prompt" -AsSecureString
    $BSTR = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($input)
    return [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($BSTR)
}

function Test-CommandExists {
    param([string]$Command)
    $null -ne (Get-Command $Command -ErrorAction SilentlyContinue)
}

function Generate-RandomBase64 {
    param([int]$Bytes = 32)
    $rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
    $bytes = New-Object byte[] $Bytes
    $rng.GetBytes($bytes)
    return [Convert]::ToBase64String($bytes)
}

function Get-ServerIP {
    $ip = (Get-NetIPAddress -AddressFamily IPv4 |
        Where-Object { $_.IPAddress -ne '127.0.0.1' -and $_.PrefixOrigin -ne 'WellKnown' } |
        Select-Object -First 1).IPAddress
    if (-not $ip) { $ip = 'localhost' }
    return $ip
}

# ═══════════════════════════════════════════════════════════════════
# PASO 1: VERIFICAR PRIVILEGIOS
# ═══════════════════════════════════════════════════════════════════
function Step-CheckAdmin {
    Write-Log "Verificando privilegios de administrador..." 'STEP'
    $currentPrincipal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    if ($currentPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        Write-Log "Privilegios de administrador confirmados." 'SUCCESS'
        return $true
    } else {
        Write-Log "Este script requiere privilegios de administrador." 'ERROR'
        Write-Log "Ejecutar PowerShell como Administrador e intentar de nuevo." 'ERROR'
        return $false
    }
}

# ═══════════════════════════════════════════════════════════════════
# PASO 2: VERIFICAR/INSTALAR PREREQUISITOS
# ═══════════════════════════════════════════════════════════════════
function Step-CheckPrerequisites {
    Write-Log "Verificando prerequisitos..." 'STEP'
    $prereqs = @{ Java = $false; Node = $false; Maven = $false }

    # 2a: Java 21
    Write-Log "Verificando Java 21..."
    try {
        $javaVer = & java -version 2>&1 | Select-String -Pattern '"(\d+)' | ForEach-Object { $_.Matches[0].Groups[1].Value }
        if ($javaVer -eq '21') {
            Write-Log "Java 21 encontrado." 'SUCCESS'
            $prereqs.Java = $true
        } else {
            throw "Java 21 no encontrado"
        }
    } catch {
        Write-Log "Java 21 no esta instalado. Intentando instalar via winget..." 'WARN'
        try {
            & winget install EclipseAdoptium.Temurin.21.JDK --accept-package-agreements --accept-source-agreements
            if ($LASTEXITCODE -eq 0) {
                Write-Log "Java 21 instalado correctamente." 'SUCCESS'
                $prereqs.Java = $true
            } else { throw "winget fallo" }
        } catch {
            Write-Log "No se pudo instalar Java automaticamente." 'ERROR'
            Write-Log "Descargar manualmente: https://adoptium.net/temurin/releases/?version=21" 'WARN'
            Start-Process "https://adoptium.net/temurin/releases/?version=21"
            Read-Host "  Presione ENTER despues de instalar Java 21"
        }
    }

    # 2b: Node.js / NPM
    Write-Log "Verificando Node.js y NPM..."
    if (Test-CommandExists 'npm') {
        $npmVer = & npm --version 2>&1
        Write-Log "Node.js/NPM encontrado (npm v$npmVer)." 'SUCCESS'
        $prereqs.Node = $true
    } else {
        Write-Log "Node.js no esta instalado. Intentando instalar via winget..." 'WARN'
        try {
            & winget install OpenJS.NodeJS.LTS --accept-package-agreements --accept-source-agreements
            if ($LASTEXITCODE -eq 0) {
                Write-Log "Node.js instalado correctamente." 'SUCCESS'
                $prereqs.Node = $true
            } else { throw "winget fallo" }
        } catch {
            Write-Log "No se pudo instalar Node.js automaticamente." 'ERROR'
            Write-Log "Descargar manualmente: https://nodejs.org/" 'WARN'
            Start-Process "https://nodejs.org/"
            Read-Host "  Presione ENTER despues de instalar Node.js"
        }
    }

    # 2c: Maven
    Write-Log "Verificando Maven..."
    if (Test-CommandExists 'mvn') {
        $mvnVer = & mvn --version 2>&1 | Select-String -Pattern 'Apache Maven' | ForEach-Object { $_.Line }
        Write-Log "Maven encontrado ($mvnVer)." 'SUCCESS'
        $prereqs.Maven = $true
    } else {
        Write-Log "Maven no esta instalado. Intentando instalar via winget..." 'WARN'
        try {
            & winget install Apache.Maven --accept-package-agreements --accept-source-agreements
            if ($LASTEXITCODE -eq 0) {
                Write-Log "Maven instalado correctamente." 'SUCCESS'
                $prereqs.Maven = $true
            } else { throw "winget fallo" }
        } catch {
            Write-Log "No se pudo instalar Maven automaticamente." 'ERROR'
            Write-Log "Descargar manualmente: https://maven.apache.org/download.cgi" 'WARN'
            Start-Process "https://maven.apache.org/download.cgi"
            Read-Host "  Presione ENTER despues de instalar Maven"
        }
    }

    return $prereqs
}

# ═══════════════════════════════════════════════════════════════════
# PASO 3: CONFIGURAR BASE DE DATOS
# ═══════════════════════════════════════════════════════════════════
function Step-ConfigureDatabase {
    Write-Log "Configurar conexion a base de datos..." 'STEP'

    $dbConfig = @{
        Type     = ''
        Host     = ''
        Port     = '3306'
        Name     = 'control_eat_food'
        User     = 'admin'
        Password = ''
        Url      = ''
    }

    Show-Menu "Donde esta la base de datos MySQL?" @(
        "Local (este servidor)",
        "Servidor remoto Linux"
    )
    $dbChoice = Read-Choice "Seleccionar opcion" -Max 2

    if ($dbChoice -eq 1) {
        # === DB LOCAL ===
        $dbConfig.Type = 'local'
        Write-Log "Configuracion de base de datos local..."

        # Verificar si ya hay algo en puerto 3306
        $portInUse = $false
        try {
            $tcp = New-Object System.Net.Sockets.TcpClient
            $tcp.Connect('localhost', 3306)
            $portInUse = $true
            $tcp.Close()
        } catch { $portInUse = $false }

        if ($portInUse) {
            Write-Log "Se detecto un servicio en puerto 3306 (MySQL ya esta corriendo)." 'SUCCESS'
            $dbConfig.Host = 'localhost'
        } else {
            Show-Menu "Como desea instalar MySQL local?" @(
                "Docker (contenedor control-mysql)",
                "MySQL instalado localmente (requiere mysql en PATH)",
                "Omitir (yo lo configuro despues)"
            )
            $mysqlChoice = Read-Choice "Seleccionar opcion" -Max 3

            switch ($mysqlChoice) {
                1 {
                    # Docker
                    Write-Log "Verificando Docker..."
                    if (-not (Test-CommandExists 'docker')) {
                        Write-Log "Docker no esta instalado." 'ERROR'
                        Write-Log "Instalar Docker Desktop: https://www.docker.com/products/docker-desktop/" 'WARN'
                        Start-Process "https://www.docker.com/products/docker-desktop/"
                        Read-Host "  Presione ENTER cuando Docker este instalado"
                    }
                    $dbConfig.Password = Read-Default "Contrasena para MySQL root/admin" "BN2002sg"
                    $dbConfig.User = 'admin'
                    $dbConfig.Host = 'localhost'

                    # Verificar si el contenedor ya existe
                    $containerExists = $false
                    try {
                        $containers = & docker ps -a --format "{{.Names}}" 2>&1
                        if ($containers -match 'control-mysql') { $containerExists = $true }
                    } catch { }

                    if ($containerExists) {
                        Write-Log "Contenedor 'control-mysql' ya existe. Iniciandolo..."
                        & docker start control-mysql 2>&1 | Out-Null
                        Write-Log "Contenedor 'control-mysql' iniciado." 'SUCCESS'
                    } else {
                        Write-Log "Creando contenedor Docker MySQL 8.0..."
                        & docker run --name control-mysql -p 3306:3306 `
                            -e MYSQL_ROOT_PASSWORD=$dbConfig.Password `
                            -e MYSQL_DATABASE=$dbConfig.Name `
                            -e MYSQL_USER=$dbConfig.User `
                            -e MYSQL_PASSWORD=$dbConfig.Password `
                            -d mysql:8.0
                        if ($LASTEXITCODE -eq 0) {
                            Write-Log "Contenedor Docker creado e iniciado." 'SUCCESS'
                        } else {
                            Write-Log "Error al crear contenedor Docker." 'ERROR'
                        }
                    }
                }
                2 {
                    # MySQL local
                    if (-not (Test-CommandExists 'mysql')) {
                        Write-Log "'mysql' no encontrado en PATH." 'ERROR'
                        Write-Log "Instalar MySQL Server o agregar al PATH." 'WARN'
                        Read-Host "  Presione ENTER cuando MySQL este configurado"
                    } else {
                        $dbConfig.Password = Read-Default "Contrasena para usuario admin" "BN2002sg"
                        $rootPass = Read-Host "  Contrasena de root en MySQL local (ENTER para omitir)"
                        if ($rootPass) {
                            & mysql -u root -p"$rootPass" -e "CREATE DATABASE IF NOT EXISTS $($dbConfig.Name) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER IF NOT EXISTS '$($dbConfig.User)'@'localhost' IDENTIFIED BY '$($dbConfig.Password)'; GRANT ALL PRIVILEGES ON $($dbConfig.Name).* TO '$($dbConfig.User)'@'localhost'; FLUSH PRIVILEGES;" 2>&1 | Out-Null
                            if ($LASTEXITCODE -eq 0) {
                                Write-Log "Base de datos y usuario configurados localmente." 'SUCCESS'
                            } else {
                                Write-Log "Error al configurar MySQL local." 'ERROR'
                            }
                        }
                        $dbConfig.Host = 'localhost'
                    }
                }
                3 {
                    Write-Log "Configuracion de DB omitida. Configurar manualmente." 'WARN'
                    $dbConfig.Host = Read-Default "Host de MySQL" "localhost"
                    $dbConfig.Port = Read-Default "Puerto de MySQL" "3306"
                    $dbConfig.Name = Read-Default "Nombre de base de datos" "control_eat_food"
                    $dbConfig.User = Read-Default "Usuario de MySQL" "admin"
                    $dbConfig.Password = Read-Default "Contrasena de MySQL" "BN2002sg"
                }
            }
        }
        $dbConfig.Url = "jdbc:mysql://localhost:$($dbConfig.Port)/$($dbConfig.Name)?createDatabaseIfNotExist=true&serverTimezone=America/Guayaquil"
    }
    else {
        # === DB REMOTA ===
        $dbConfig.Type = 'remote'
        Write-Log "Configuracion de base de datos REMOTA..."

        $dbConfig.Host = Read-Host "  IP/Host del servidor MySQL"
        $dbConfig.Port = Read-Default "Puerto" "3306"
        $dbConfig.Name = Read-Default "Nombre de base de datos" "control_eat_food"
        $dbConfig.User = Read-Default "Usuario" "admin"
        $dbConfig.Password = Read-SecureInput "Contrasena"

        # Test de conexion
        Write-Log "Probando conexion a $($dbConfig.Host):$($dbConfig.Port)..."
        $connSuccess = $false
        try {
            $tcp = New-Object System.Net.Sockets.TcpClient
            $tcp.Connect($dbConfig.Host, [int]$dbConfig.Port)
            $tcp.Close()
            $connSuccess = $true
            Write-Log "Conexion TCP exitosa a $($dbConfig.Host):$($dbConfig.Port)." 'SUCCESS'
        } catch {
            Write-Log "No se pudo conectar a $($dbConfig.Host):$($dbConfig.Port)" 'ERROR'
            Write-Log "Verificar: firewall, que MySQL acepte conexiones remotas, credenciales." 'WARN'
        }

        # Intentar test con mysql client si esta disponible
        if ($connSuccess -and (Test-CommandExists 'mysql')) {
            Write-Log "Probando autenticacion MySQL..."
            $env:MYSQL_PWD = $dbConfig.Password
            & mysql -h $dbConfig.Host -P $dbConfig.Port -u $dbConfig.User -e "SELECT 1" 2>&1 | Out-Null
            $mysqlResult = $LASTEXITCODE
            Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
            if ($mysqlResult -eq 0) {
                Write-Log "Autenticacion MySQL exitosa." 'SUCCESS'
            } else {
                Write-Log "Autenticacion MySQL fallo. Verificar usuario/contrasena." 'ERROR'
                $retry = Read-Host "  Reintentar? (s/n)"
                if ($retry -eq 's') { return (Step-ConfigureDatabase) }
            }
        }

        if (-not $connSuccess) {
            Write-Log "La conexion TCP fallo. Desea continuar de todos modos?" 'WARN'
            $cont = Read-Host "  Continuar? (s/n)"
            if ($cont -ne 's') { return (Step-ConfigureDatabase) }
        }

        $dbConfig.Url = "jdbc:mysql://$($dbConfig.Host):$($dbConfig.Port)/$($dbConfig.Name)?useSSL=false&serverTimezone=America/Guayaquil&allowPublicKeyRetrieval=true"
    }

    Write-Log "DB_URL = $($dbConfig.Url)" 'SUCCESS'
    return $dbConfig
}

# ═══════════════════════════════════════════════════════════════════
# PASO 4: CONFIGURAR VARIABLES DE PRODUCCION
# ═══════════════════════════════════════════════════════════════════
function Step-ConfigureProduction {
    param($DbConfig)
    Write-Log "Configurar variables de produccion..." 'STEP'

    $prodConfig = @{
        JwtSecret              = ''
        CorsOrigins            = ''
        PublicUrl              = ''
        BiometricEncryptionKey = ''
        RateLimitEnabled       = 'true'
        RateLimitAuth          = '10'
        RateLimitScan          = '60'
        BackendPort            = '8080'
        FrontendPort           = '80'
        ServerIP               = (Get-ServerIP)
    }

    # JWT Secret
    Write-Host ""
    Write-Host "  --- JWT Secret ---" -ForegroundColor Yellow
    Write-Host "    [1] Generar automaticamente (recomendado)"
    Write-Host "    [2] Ingresar manualmente"
    $jwtChoice = Read-Choice "Seleccionar" -Max 2
    if ($jwtChoice -eq 1) {
        $prodConfig.JwtSecret = Generate-RandomBase64 -Bytes 32
        Write-Log "JWT Secret generado automaticamente." 'SUCCESS'
    } else {
        $prodConfig.JwtSecret = Read-Host "  Ingrese JWT Secret (Base64, min 256 bits)"
    }

    # CORS Origins
    Write-Host ""
    Write-Host "  --- CORS Origins ---" -ForegroundColor Yellow
    $defaultCors = "http://$($prodConfig.ServerIP),http://localhost"
    Write-Host "    IP detectada del servidor: $($prodConfig.ServerIP)"
    $prodConfig.CorsOrigins = Read-Default "Origenes CORS (separar por coma)" $defaultCors

    # Public URL
    Write-Host ""
    Write-Host "  --- URL Publica ---" -ForegroundColor Yellow
    Write-Host "    [1] Auto-detectar (vacio)"
    Write-Host "    [2] Ingresar manualmente (ej: https://catering.empresa.com)"
    $urlChoice = Read-Choice "Seleccionar" -Max 2
    if ($urlChoice -eq 2) {
        $prodConfig.PublicUrl = Read-Host "  URL publica"
    }

    # Biometric Encryption Key
    Write-Host ""
    Write-Host "  --- Biometric Encryption Key (AES) ---" -ForegroundColor Yellow
    Write-Host "    [1] Generar automaticamente (recomendado)"
    Write-Host "    [2] Ingresar manualmente"
    Write-Host "    [3] Dejar vacio (no cifrar plantillas)"
    $bioChoice = Read-Choice "Seleccionar" -Max 3
    switch ($bioChoice) {
        1 {
            $prodConfig.BiometricEncryptionKey = Generate-RandomBase64 -Bytes 16
            Write-Log "Biometric Encryption Key generada." 'SUCCESS'
        }
        2 { $prodConfig.BiometricEncryptionKey = Read-Host "  Ingrese clave AES (min 16 bytes Base64)" }
        3 { $prodConfig.BiometricEncryptionKey = '' }
    }

    # Rate Limit
    Write-Host ""
    Write-Host "  --- Rate Limit ---" -ForegroundColor Yellow
    Write-Host "    [1] Habilitado (recomendado para produccion)"
    Write-Host "    [2] Deshabilitado"
    $rlChoice = Read-Choice "Seleccionar" -Max 2
    $prodConfig.RateLimitEnabled = if ($rlChoice -eq 1) { 'true' } else { 'false' }

    # Puertos
    Write-Host ""
    Write-Host "  --- Puertos ---" -ForegroundColor Yellow
    $prodConfig.BackendPort = Read-Default "Puerto backend (Spring Boot)" "8080"
    $prodConfig.FrontendPort = Read-Default "Puerto frontend (reverse proxy)" "80"

    return $prodConfig
}

# ═══════════════════════════════════════════════════════════════════
# PASO 5: INSTALAR DEPENDENCIAS FRONTEND
# ═══════════════════════════════════════════════════════════════════
function Step-InstallFrontendDeps {
    Write-Log "Instalando dependencias del frontend..." 'STEP'

    if (-not (Test-Path (Join-Path $FrontendDir "package.json"))) {
        Write-Log "No se encontro package.json en $FrontendDir" 'ERROR'
        return $false
    }

    Push-Location $FrontendDir
    try {
        if (Test-Path "node_modules") {
            Write-Log "node_modules ya existe. Verificando..."
        } else {
            Write-Log "Ejecutando npm install..."
            & npm install 2>&1 | ForEach-Object { Write-Log $_ }
            if ($LASTEXITCODE -ne 0) { throw "npm install fallo" }
            Write-Log "Dependencias instaladas." 'SUCCESS'
        }

        # Verificar qrcode
        if (-not (Test-Path "node_modules\qrcode")) {
            Write-Log "Instalando dependencia adicional: qrcode..."
            & npm install qrcode 2>&1 | Out-Null
            Write-Log "qrcode instalado." 'SUCCESS'
        } else {
            Write-Log "qrcode ya esta instalado." 'SUCCESS'
        }

        return $true
    } finally {
        Pop-Location
    }
}

# ═══════════════════════════════════════════════════════════════════
# PASO 6: BUILD DE PRODUCCION
# ═══════════════════════════════════════════════════════════════════
function Step-BuildProduction {
    param($DbConfig, $ProdConfig)
    Write-Log "Generando build de produccion..." 'STEP'

    # 6a: Generar application-prod.yml
    Write-Log "Generando application-prod.yml..."
    $zkNativePath = Join-Path $ScriptRoot "native"
    $ymlContent = @"
server:
  port: $($ProdConfig.BackendPort)
  servlet:
    context-path: /
  compression:
    enabled: true
    mime-types: application/json,application/xml,text/html,text/plain,text/css,application/javascript
    min-response-size: 1024

spring:
  application:
    name: control-eat-food
  datasource:
    url: "$($DbConfig.Url)"
    username: "$($DbConfig.User)"
    password: "$($DbConfig.Password)"
    hikari:
      maximum-pool-size: 15
      minimum-idle: 3
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        format_sql: false
        jdbc:
          time_zone: America/Guayaquil
        timezone:
          default_storage: NORMALIZE
    open-in-view: false
  flyway:
    enabled: true
    baseline-on-migrate: false
    locations: classpath:db/migration
  jackson:
    time-zone: America/Guayaquil
    serialization:
      write-dates-as-timestamps: false

app:
  public-url: "$($ProdConfig.PublicUrl)"
  security:
    jwt:
      secret: "$($ProdConfig.JwtSecret)"
      access-token-minutes: 30
      refresh-token-days: 7
    brute-force:
      max-attempts: 5
      lock-minutes: 15
  restaurant:
    max-devices: 2
  biometric:
    provider: zk
    match-threshold: 70
    encryption-key: "$($ProdConfig.BiometricEncryptionKey)"
    native-lib-path: "$zkNativePath"
  cors:
    allowed-origins: "$($ProdConfig.CorsOrigins)"
  rate-limit:
    enabled: $($ProdConfig.RateLimitEnabled)
    auth-per-minute: $($ProdConfig.RateLimitAuth)
    scan-per-minute: $($ProdConfig.RateLimitScan)

springdoc:
  swagger-ui:
    path: /swagger-ui.html
  api-docs:
    path: /v3/api-docs

logging:
  level:
    com.eatfood: INFO
    org.springframework.security: WARN
"@
    Set-Content -Path $ProdYmlPath -Value $ymlContent -Encoding UTF8
    Write-Log "application-prod.yml generado en: $ProdYmlPath" 'SUCCESS'

    # 6b: Generar .env.production para frontend
    Write-Log "Generando .env.production para frontend..."
    $serverIP = $ProdConfig.ServerIP
    $wsUrl = "ws://$serverIP`:$($ProdConfig.BackendPort)/zkfinger-ws"
    $envContent = @"
# Generado automaticamente por install.ps1
VITE_API_BASE_URL=http://$serverIP`:$($ProdConfig.BackendPort)
VITE_ZKFINGER_WS=$wsUrl
"@
    Set-Content -Path (Join-Path $FrontendDir ".env.production") -Value $envContent -Encoding UTF8
    Write-Log ".env.production generado." 'SUCCESS'

    # 6c: Build Frontend
    Write-Log "Compilando frontend (npm run build)..."
    Push-Location $FrontendDir
    try {
        & npm run build 2>&1 | ForEach-Object { Write-Log $_ }
        if ($LASTEXITCODE -ne 0) { throw "npm run build fallo" }
        Write-Log "Frontend compilado (dist/ generado)." 'SUCCESS'
    } finally {
        Pop-Location
    }

    # 6d: Build Backend
    Write-Log "Compilando backend (mvn clean package)..."
    Push-Location $BackendDir
    try {
        # Copiar application-prod.yml al classpath
        $targetResources = Join-Path $BackendDir "src\main\resources"
        Copy-Item $ProdYmlPath (Join-Path $targetResources "application-prod.yml") -Force

        & mvn clean package -DskipTests -Dspring.profiles.active=prod 2>&1 | ForEach-Object {
            if ($_ -match 'BUILD SUCCESS') { Write-Log $_ 'SUCCESS' }
            elseif ($_ -match 'BUILD FAILURE|ERROR') { Write-Log $_ 'ERROR' }
        }
        if ($LASTEXITCODE -ne 0) { throw "mvn package fallo" }

        # Buscar el JAR generado
        $jarFile = Get-ChildItem (Join-Path $BackendDir "target") -Filter "*.jar" |
            Where-Object { $_.Name -notmatch 'sources|javadoc' } |
            Select-Object -First 1
        if ($jarFile) {
            Write-Log "Backend compilado: $($jarFile.FullName)" 'SUCCESS'
        } else {
            Write-Log "No se encontro el JAR generado." 'ERROR'
        }
    } finally {
        Pop-Location
    }
}

# ═══════════════════════════════════════════════════════════════════
# PASO 7: SETUP.EXE BIOMETRICO
# ═══════════════════════════════════════════════════════════════════
function Step-RunBiometricSetup {
    Write-Log "Verificando setup.exe (SDK biometrico)..." 'STEP'

    $setupExe = Join-Path $ScriptRoot "setup.exe"
    $lockFile = Join-Path $ScriptRoot ".setup_completado.lock"

    if (Test-Path $lockFile) {
        Write-Log "setup.exe ya fue ejecutado previamente (lock file existe)." 'SUCCESS'
        return
    }

    if (-not (Test-Path $setupExe)) {
        Write-Log "setup.exe no encontrado en $ScriptRoot" 'WARN'
        return
    }

    Write-Log "Ejecutando setup.exe para instalar SDK del lector biometrico..."
    Start-Process -FilePath $setupExe -Wait
    "OK" | Set-Content -Path $lockFile -Encoding ASCII
    Write-Log "setup.exe finalizado." 'SUCCESS'
}

# ═══════════════════════════════════════════════════════════════════
# PASO 8: CONFIGURAR SERVICIO WINDOWS (NSSM)
# ═══════════════════════════════════════════════════════════════════
function Step-ConfigureService {
    param($DbConfig, $ProdConfig)
    Write-Log "Configurando servicio de Windows..." 'STEP'

    # Buscar el JAR
    $jarFile = Get-ChildItem (Join-Path $BackendDir "target") -Filter "*.jar" |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } |
        Select-Object -First 1

    if (-not $jarFile) {
        Write-Log "No se encontro JAR del backend. No se puede crear servicio." 'ERROR'
        return $false
    }

    # Descargar NSSM si no existe
    if (-not (Test-Path $NssmExe)) {
        Write-Log "Descargando NSSM (Non-Sucking Service Manager)..."
        try {
            $nssmUrl = "https://nssm.cc/release/nssm-2.24.zip"
            $zipPath = Join-Path $NssmDir "nssm.zip"
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            Invoke-WebRequest -Uri $nssmUrl -OutFile $zipPath -UseBasicParsing
            Expand-Archive -Path $zipPath -DestinationPath $NssmDir -Force
            # Buscar nssm.exe dentro de la estructura extraida
            $foundNssm = Get-ChildItem $NssmDir -Recurse -Filter "nssm.exe" |
                Where-Object { $_.DirectoryName -match 'win64' } | Select-Object -First 1
            if (-not $foundNssm) {
                $foundNssm = Get-ChildItem $NssmDir -Recurse -Filter "nssm.exe" | Select-Object -First 1
            }
            if ($foundNssm) {
                Copy-Item $foundNssm.FullName $NssmExe -Force
                Write-Log "NSSM descargado: $NssmExe" 'SUCCESS'
            }
            Remove-Item $zipPath -Force -ErrorAction SilentlyContinue
        } catch {
            Write-Log "No se pudo descargar NSSM automaticamente." 'ERROR'
            Write-Log "Descargar manualmente de: https://nssm.cc/download" 'WARN'
            Write-Log "Colocar nssm.exe en: $NssmDir" 'WARN'
            Start-Process "https://nssm.cc/download"
            Read-Host "  Presione ENTER cuando NSSM este instalado"
            if (-not (Test-Path $NssmExe)) {
                Write-Log "NSSM no encontrado. Servicio no creado." 'ERROR'
                return $false
            }
        }
    }

    # Detener servicio si ya existe
    $existingService = & $NssmExe list 2>&1 | Where-Object { $_ -eq $ServiceName }
    if ($existingService) {
        Write-Log "Servicio '$ServiceName' ya existe. Deteniendo y eliminando..."
        & $NssmExe stop $ServiceName confirm 2>&1 | Out-Null
        & $NssmExe remove $ServiceName confirm 2>&1 | Out-Null
        Start-Sleep -Seconds 2
    }

    # Crear servicio
    Write-Log "Registrando servicio '$ServiceName'..."
    $javaExe = (Get-Command java).Source
    $jarPath = $jarFile.FullName

    & $NssmExe install $ServiceName $javaExe "-jar `"$jarPath`" --spring.profiles.active=prod --spring.config.additional-location=file:`"$ProdYmlPath`""
    if ($LASTEXITCODE -ne 0) {
        Write-Log "Error al registrar servicio." 'ERROR'
        return $false
    }

    # Configurar variables de entorno del servicio
    & $NssmExe set $ServiceName AppEnvironmentExtra "DB_URL=$($DbConfig.Url)" "DB_USER=$($DbConfig.User)" "DB_PASSWORD=$($DbConfig.Password)" "JWT_SECRET=$($ProdConfig.JwtSecret)" "CORS_ORIGINS=$($ProdConfig.CorsOrigins)" "PUBLIC_URL=$($ProdConfig.PublicUrl)" "BIOMETRIC_ENCRYPTION_KEY=$($ProdConfig.BiometricEncryptionKey)" "RATE_LIMIT_ENABLED=$($ProdConfig.RateLimitEnabled)" "ZK_NATIVE_PATH=$zkNativePath"

    # Configurar directorio de trabajo
    & $NssmExe set $ServiceName AppDirectory $BackendDir

    # Configurar restart automatico
    & $NssmExe set $ServiceName AppExit Default Restart
    & $NssmExe set $ServiceName AppRestartDelay 10000

    # Configurar descripcion
    & $NssmExe set $ServiceName Description "ControlEatFood - Backend Spring Boot (Produccion)"
    & $NssmExe set $ServiceName DisplayName "Control Eat Food"
    & $NssmExe set $ServiceName Start SERVICE_AUTO_START

    # Configurar logs
    $serviceLogsDir = Join-Path $LogsDir "service"
    if (-not (Test-Path $serviceLogsDir)) { New-Item -ItemType Directory -Path $serviceLogsDir -Force | Out-Null }
    & $NssmExe set $ServiceName AppStdout (Join-Path $serviceLogsDir "stdout.log")
    & $NssmExe set $ServiceName AppStderr (Join-Path $serviceLogsDir "stderr.log")
    & $NssmExe set $ServiceName AppStderrCreationDisposition 4
    & $NssmExe set $ServiceName AppStdoutCreationDisposition 4

    # Iniciar servicio
    Write-Log "Iniciando servicio '$ServiceName'..."
    & $NssmExe start $ServiceName
    Start-Sleep -Seconds 5

    # Verificar estado
    $status = & $NssmExe status $ServiceName 2>&1
    if ($status -match 'SERVICE_RUNNING') {
        Write-Log "Servicio '$ServiceName' iniciado correctamente." 'SUCCESS'
    } else {
        Write-Log "El servicio podria no haber iniciado. Estado: $status" 'WARN'
        Write-Log "Verificar logs en: $serviceLogsDir" 'WARN'
    }

    return $true
}

# ═══════════════════════════════════════════════════════════════════
# PASO 9: CONFIGURAR FIREWALL
# ═══════════════════════════════════════════════════════════════════
function Step-ConfigureFirewall {
    param($ProdConfig)
    Write-Log "Configurando firewall de Windows..." 'STEP'

    $backendPort = $ProdConfig.BackendPort
    $frontendPort = $ProdConfig.FrontendPort

    # Regla para backend
    $ruleNameBE = "ControlEatFood Backend (Port $backendPort)"
    $existingRule = Get-NetFirewallRule -DisplayName $ruleNameBE -ErrorAction SilentlyContinue
    if ($existingRule) {
        Write-Log "Regla de firewall para backend ya existe." 'SUCCESS'
    } else {
        try {
            New-NetFirewallRule -DisplayName $ruleNameBE -Direction Inbound -Protocol TCP -LocalPort $backendPort -Action Allow -Profile Any | Out-Null
            Write-Log "Regla de firewall creada: $ruleNameBE" 'SUCCESS'
        } catch {
            Write-Log "No se pudo crear regla de firewall para backend." 'ERROR'
        }
    }

    # Regla para frontend (si puerto diferente)
    if ($frontendPort -ne $backendPort) {
        $ruleNameFE = "ControlEatFood Frontend (Port $frontendPort)"
        $existingRuleFE = Get-NetFirewallRule -DisplayName $ruleNameFE -ErrorAction SilentlyContinue
        if ($existingRuleFE) {
            Write-Log "Regla de firewall para frontend ya existe." 'SUCCESS'
        } else {
            try {
                New-NetFirewallRule -DisplayName $ruleNameFE -Direction Inbound -Protocol TCP -LocalPort $frontendPort -Action Allow -Profile Any | Out-Null
                Write-Log "Regla de firewall creada: $ruleNameFE" 'SUCCESS'
            } catch {
                Write-Log "No se pudo crear regla de firewall para frontend." 'ERROR'
            }
        }
    }
}

# ═══════════════════════════════════════════════════════════════════
# GUARDAR CONFIGURACION
# ═══════════════════════════════════════════════════════════════════
function Save-Config {
    param($DbConfig, $ProdConfig)
    Write-Log "Guardando configuracion..." 'STEP'

    $config = @{
        installDate    = (Get-Date -Format "yyyy-MM-dd HH:mm:ss")
        serverIP       = $ProdConfig.ServerIP
        database       = $DbConfig
        production     = @{
            jwtSecret              = $ProdConfig.JwtSecret
            corsOrigins            = $ProdConfig.CorsOrigins
            publicUrl              = $ProdConfig.PublicUrl
            biometricEncryptionKey = $ProdConfig.BiometricEncryptionKey
            rateLimitEnabled       = $ProdConfig.RateLimitEnabled
            backendPort            = $ProdConfig.BackendPort
            frontendPort           = $ProdConfig.FrontendPort
        }
        service        = @{
            name   = $ServiceName
            status = "installed"
        }
        paths          = @{
            projectRoot  = $ProjectRoot
            backendDir   = $BackendDir
            frontendDir  = $FrontendDir
            prodYml      = $ProdYmlPath
            nssmExe      = $NssmExe
        }
    }

    $config | ConvertTo-Json -Depth 5 | Set-Content -Path $ConfigFile -Encoding UTF8
    Write-Log "Configuracion guardada en: $ConfigFile" 'SUCCESS'
}

# ═══════════════════════════════════════════════════════════════════
# INSTALACION COMPLETA
# ═══════════════════════════════════════════════════════════════════
function Install-Full {
    Write-Host ""
    Write-Host "  ============================================================" -ForegroundColor Green
    Write-Host "  INICIANDO INSTALACION DE PRODUCCION" -ForegroundColor Green
    Write-Host "  ============================================================" -ForegroundColor Green

    # Paso 1: Admin
    if (-not (Step-CheckAdmin)) { return }

    # Paso 2: Prerequisitos
    $prereqs = Step-CheckPrerequisites
    Write-Host ""
    Write-Host "  Resumen prerequisitos:" -ForegroundColor Yellow
    Write-Host "    Java 21:  $(if($prereqs.Java){'[OK]'}else{'[FALLO]'})"
    Write-Host "    Node.js:  $(if($prereqs.Node){'[OK]'}else{'[FALLO]'})"
    Write-Host "    Maven:    $(if($prereqs.Maven){'[OK]'}else{'[FALLO]'})"
    Write-Host ""

    $continue = Read-Host "  Continuar con la instalacion? (s/n)"
    if ($continue -ne 's') { Write-Log "Instalacion cancelada por el usuario."; return }

    # Paso 3: Base de datos
    $dbConfig = Step-ConfigureDatabase

    # Paso 4: Variables de produccion
    $prodConfig = Step-ConfigureProduction -DbConfig $dbConfig

    # Paso 5: Dependencias frontend
    $feDeps = Step-InstallFrontendDeps
    if (-not $feDeps) {
        Write-Log "Error en dependencias del frontend. Abortando." 'ERROR'
        return
    }

    # Paso 6: Build produccion
    Step-BuildProduction -DbConfig $dbConfig -ProdConfig $prodConfig

    # Paso 7: Setup biometrico
    Step-RunBiometricSetup

    # Paso 8: Servicio Windows
    $serviceOk = Step-ConfigureService -DbConfig $dbConfig -ProdConfig $prodConfig

    # Paso 9: Firewall
    Step-ConfigureFirewall -ProdConfig $prodConfig

    # Guardar config
    Save-Config -DbConfig $dbConfig -ProdConfig $prodConfig

    # Resumen final
    Write-Host ""
    Write-Host "  ============================================================" -ForegroundColor Green
    Write-Host "  INSTALACION COMPLETADA" -ForegroundColor Green
    Write-Host "  ============================================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "  Servicio:     $ServiceName" -ForegroundColor Cyan
    if ($serviceOk) {
        Write-Host "  Estado:       CORRIENDO" -ForegroundColor Green
    } else {
        Write-Host "  Estado:       VERIFICAR (posible error)" -ForegroundColor Yellow
    }
    Write-Host "  Backend:      http://$($prodConfig.ServerIP):$($prodConfig.BackendPort)" -ForegroundColor Cyan
    Write-Host "  Swagger:      http://$($prodConfig.ServerIP):$($prodConfig.BackendPort)/swagger-ui.html" -ForegroundColor Cyan
    Write-Host "  DB:           $($dbConfig.Host):$($dbConfig.Port)/$($dbConfig.Name)" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "  Config:       $ConfigFile" -ForegroundColor DarkGray
    Write-Host "  Log:          $LogFile" -ForegroundColor DarkGray
    Write-Host ""
    Write-Host "  Comandos utiles:" -ForegroundColor Yellow
    Write-Host "    nssm status $ServiceName          - Ver estado"
    Write-Host "    nssm restart $ServiceName         - Reiniciar"
    Write-Host "    nssm stop $ServiceName            - Detener"
    Write-Host "    nssm edit $ServiceName            - Editar configuracion"
    Write-Host ""
}

# ═══════════════════════════════════════════════════════════════════
# ACTUALIZAR
# ═══════════════════════════════════════════════════════════════════
function Update-App {
    Write-Log "Actualizando aplicacion..." 'STEP'

    if (-not (Test-Path $ConfigFile)) {
        Write-Log "No se encontro config. Ejecutar instalacion nueva primero." 'ERROR'
        return
    }

    $config = Get-Content $ConfigFile | ConvertFrom-Json

    Write-Log "Deteniendo servicio..."
    if (Test-Path $NssmExe) {
        & $NssmExe stop $ServiceName confirm 2>&1 | Out-Null
    }

    # Rebuild
    Write-Log "Recompilando frontend..."
    Push-Location $FrontendDir
    & npm run build 2>&1 | ForEach-Object { Write-Log $_ }
    Pop-Location

    Write-Log "Recompilando backend..."
    Push-Location $BackendDir
    & mvn clean package -DskipTests 2>&1 | ForEach-Object {
        if ($_ -match 'BUILD SUCCESS') { Write-Log $_ 'SUCCESS' }
        elseif ($_ -match 'BUILD FAILURE') { Write-Log $_ 'ERROR' }
    }
    Pop-Location

    # Reiniciar servicio
    if (Test-Path $NssmExe) {
        & $NssmExe start $ServiceName 2>&1 | Out-Null
        Start-Sleep -Seconds 3
        $status = & $NssmExe status $ServiceName 2>&1
        Write-Log "Servicio reiniciado. Estado: $status" 'SUCCESS'
    }

    Write-Log "Actualizacion completada." 'SUCCESS'
}

# ═══════════════════════════════════════════════════════════════════
# REPARAR
# ═══════════════════════════════════════════════════════════════════
function Repair-App {
    Write-Log "Menu de reparacion..." 'STEP'

    Show-Menu "Que desea reparar?" @(
        "Reconectar base de datos",
        "Regenerar application-prod.yml",
        "Reinstalar servicio Windows",
        "Reconfigurar firewall",
        "Volver al menu principal"
    )
    $repairChoice = Read-Choice "Seleccionar" -Max 5

    if ($repairChoice -eq 5) { return }

    $config = $null
    if (Test-Path $ConfigFile) {
        $config = Get-Content $ConfigFile | ConvertFrom-Json
    }

    switch ($repairChoice) {
        1 {
            $dbConfig = Step-ConfigureDatabase
            if ($config) {
                $config.database = $dbConfig
                $config | ConvertTo-Json -Depth 5 | Set-Content -Path $ConfigFile -Encoding UTF8
            }
            Write-Log "Base de datos reconfigurada. Reinicie el servicio." 'SUCCESS'
        }
        2 {
            if ($config) {
                $dbCfg = [PSCustomObject]@{
                    Host = $config.database.Host; Port = $config.database.Port
                    Name = $config.database.Name; User = $config.database.User
                    Password = $config.database.Password; Url = $config.database.Url
                }
                $prodCfg = [PSCustomObject]@{
                    JwtSecret = $config.production.jwtSecret
                    CorsOrigins = $config.production.corsOrigins
                    PublicUrl = $config.production.publicUrl
                    BiometricEncryptionKey = $config.production.biometricEncryptionKey
                    RateLimitEnabled = $config.production.rateLimitEnabled
                    RateLimitAuth = '10'; RateLimitScan = '60'
                    BackendPort = $config.production.backendPort
                    FrontendPort = $config.production.frontendPort
                    ServerIP = $config.serverIP
                }
                Step-BuildProduction -DbConfig $dbCfg -ProdConfig $prodCfg
            } else {
                Write-Log "No hay config previa. Ejecutar instalacion nueva." 'ERROR'
            }
        }
        3 {
            if ($config) {
                $dbCfg = [PSCustomObject]@{
                    Host = $config.database.Host; Port = $config.database.Port
                    Name = $config.database.Name; User = $config.database.User
                    Password = $config.database.Password; Url = $config.database.Url
                }
                $prodCfg = [PSCustomObject]@{
                    JwtSecret = $config.production.jwtSecret
                    CorsOrigins = $config.production.corsOrigins
                    PublicUrl = $config.production.publicUrl
                    BiometricEncryptionKey = $config.production.biometricEncryptionKey
                    RateLimitEnabled = $config.production.rateLimitEnabled
                    RateLimitAuth = '10'; RateLimitScan = '60'
                    BackendPort = $config.production.backendPort
                    FrontendPort = $config.production.frontendPort
                    ServerIP = $config.serverIP
                }
                Step-ConfigureService -DbConfig $dbCfg -ProdConfig $prodCfg
            } else {
                Write-Log "No hay config previa. Ejecutar instalacion nueva." 'ERROR'
            }
        }
        4 {
            if ($config) {
                $prodCfg = [PSCustomObject]@{
                    BackendPort = $config.production.backendPort
                    FrontendPort = $config.production.frontendPort
                }
                Step-ConfigureFirewall -ProdConfig $prodCfg
            } else {
                Write-Log "No hay config previa. Ejecutar instalacion nueva." 'ERROR'
            }
        }
    }
}

# ═══════════════════════════════════════════════════════════════════
# DIAGNOSTICO
# ═══════════════════════════════════════════════════════════════════
function Show-Diagnostics {
    Write-Log "Ejecutando diagnostico del entorno..." 'STEP'

    Write-Host ""
    Write-Host "  --- Estado del Sistema ---" -ForegroundColor Yellow

    # Java
    try {
        $javaVer = & java -version 2>&1 | Select-Object -First 1
        Write-Host "    Java:         $javaVer" -ForegroundColor Green
    } catch { Write-Host "    Java:         NO ENCONTRADO" -ForegroundColor Red }

    # Node
    try {
        $nodeVer = & node --version 2>&1
        Write-Host "    Node.js:      $nodeVer" -ForegroundColor Green
    } catch { Write-Host "    Node.js:      NO ENCONTRADO" -ForegroundColor Red }

    # Maven
    try {
        $mvnVer = & mvn --version 2>&1 | Select-Object -First 1
        Write-Host "    Maven:        $mvnVer" -ForegroundColor Green
    } catch { Write-Host "    Maven:        NO ENCONTRADO" -ForegroundColor Red }

    # NSSM
    if (Test-Path $NssmExe) {
        Write-Host "    NSSM:         Instalado" -ForegroundColor Green
    } else {
        Write-Host "    NSSM:         NO INSTALADO" -ForegroundColor Red
    }

    # Servicio
    if (Test-Path $NssmExe) {
        $svcStatus = & $NssmExe status $ServiceName 2>&1
        Write-Host "    Servicio:     $svcStatus" -ForegroundColor $(if ($svcStatus -match 'RUNNING') { 'Green' } else { 'Yellow' })
    }

    # Config
    if (Test-Path $ConfigFile) {
        Write-Host "    Config:       EXISTE" -ForegroundColor Green
    } else {
        Write-Host "    Config:       NO EXISTE" -ForegroundColor Red
    }

    # Puertos
    Write-Host ""
    Write-Host "  --- Puertos en escucha ---" -ForegroundColor Yellow
    $ports = @(8080, 80, 3306, 443)
    foreach ($port in $ports) {
        try {
            $tcp = New-Object System.Net.Sockets.TcpClient
            $tcp.Connect('localhost', $port)
            $tcp.Close()
            Write-Host "    Puerto $port :  ABIERTO" -ForegroundColor Green
        } catch {
            Write-Host "    Puerto $port :  CERRADO" -ForegroundColor DarkGray
        }
    }

    # JAR
    $jarExists = Get-ChildItem (Join-Path $BackendDir "target") -Filter "*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|javadoc' }
    if ($jarExists) {
        Write-Host "    JAR Backend:  $($jarExists.Name)" -ForegroundColor Green
    } else {
        Write-Host "    JAR Backend:  NO GENERADO" -ForegroundColor Red
    }

    # Frontend dist
    if (Test-Path (Join-Path $FrontendDir "dist")) {
        Write-Host "    Frontend:     dist/ EXISTE" -ForegroundColor Green
    } else {
        Write-Host "    Frontend:     dist/ NO EXISTE" -ForegroundColor Red
    }

    Write-Host ""
}

# ═══════════════════════════════════════════════════════════════════
# MENU PRINCIPAL
# ═══════════════════════════════════════════════════════════════════
function Show-MainMenu {
    Clear-Host
    Write-Banner

    Show-Menu "Seleccionar opcion" @(
        "Instalacion nueva (produccion)",
        "Actualizar aplicacion (rebuild + restart)",
        "Reparar configuracion",
        "Desinstalar",
        "Diagnosticos del entorno",
        "Salir"
    )

    $choice = Read-Choice "Seleccionar opcion" -Max 6

    switch ($choice) {
        1 { Install-Full }
        2 { Update-App }
        3 { Repair-App }
        4 {
            $confirm = Read-Host "  Esta seguro de desinstalar? (s/n)"
            if ($confirm -eq 's') {
                $scriptPath = Join-Path $ScriptRoot "uninstall.ps1"
                if (Test-Path $scriptPath) {
                    & $scriptPath
                } else {
                    Write-Log "uninstall.ps1 no encontrado." 'ERROR'
                }
            }
        }
        5 { Show-Diagnostics }
        6 {
            Write-Host "`n  Saliendo...`n" -ForegroundColor Yellow
            exit 0
        }
    }

    Write-Host ""
    Read-Host "  Presione ENTER para volver al menu"
    Show-MainMenu
}

# ═══════════════════════════════════════════════════════════════════
# INICIO
# ═══════════════════════════════════════════════════════════════════
try {
    Show-MainMenu
} catch {
    Write-Log "Error inesperado: $_" 'ERROR'
    Write-Log "Stack: $($_.ScriptStackTrace)" 'ERROR'
    Read-Host "  Presione ENTER para salir"
}
