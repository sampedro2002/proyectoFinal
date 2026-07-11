<#
.SYNOPSIS
    ControlEatFood - Punto de entrada unico (Pruebas / Produccion) para Windows
.DESCRIPTION
    Reemplaza a setup_env.bat + install.ps1 + uninstall.ps1. Un solo menu con
    dos caminos:
      [1] Pruebas     -> entorno de desarrollo local (no requiere Administrador)
      [2] Produccion  -> build + servicio de Windows (requiere Administrador,
                          se auto-eleva solo)
.NOTES
    Lanzar via Inicio.bat (evita el problema de politica de ejecucion al
    hacer doble clic), o directamente:
      powershell -NoProfile -ExecutionPolicy Bypass -File Inicio.ps1
#>

[CmdletBinding()]
param(
    [ValidateSet('', 'Production')]
    [string]$Mode = ''
)

Set-StrictMode -Version Latest
# 'Continue' (no 'Stop'): con 'Stop', PowerShell 5.1 convierte cualquier linea
# que un ejecutable nativo escriba en stderr (ej. "java -version", que SIEMPRE
# imprime la version por stderr) en un error terminante en cuanto se usa
# "2>&1" -- rompiendo la deteccion aunque la herramienta si este instalada.
# Los fallos que si deben detener el flujo usan -ErrorAction Stop explicito o
# revisan $LASTEXITCODE, que no depende de esta preferencia.
$ErrorActionPreference = 'Continue'

# ═══════════════════════════════════════════════════════════════════
# CONFIGURACION GLOBAL
# ═══════════════════════════════════════════════════════════════════
$ScriptRoot   = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot  = Split-Path -Parent $ScriptRoot
$ConfigDir    = Join-Path $ScriptRoot "config"
$LogsDir      = Join-Path $ScriptRoot "logs"
$NssmDir      = Join-Path $ScriptRoot "tools"
$NssmExe      = Join-Path $NssmDir "nssm.exe"
$ServiceName  = "ControlEatFood"
$BackendDir   = Join-Path $ProjectRoot "controlEatFoodWeb\backend"
$FrontendDir  = Join-Path $ProjectRoot "controlEatFoodWeb\frontend"
$ConfigFile   = Join-Path $ConfigDir "install_config.json"
$ProdYmlPath  = Join-Path $ConfigDir "application-prod.yml"
$SetupExe     = Join-Path $ScriptRoot "setup.exe"
$LockFile     = Join-Path $ScriptRoot ".setup_completado.lock"
$ZkNativePath = Join-Path $ScriptRoot "native"

@($ConfigDir, $LogsDir, $NssmDir) | ForEach-Object {
    if (-not (Test-Path $_)) { New-Item -ItemType Directory -Path $_ -Force | Out-Null }
}
$LogFile = Join-Path $LogsDir ("inicio_{0}.log" -f (Get-Date -Format "yyyyMMdd_HHmmss"))

# Variables de sesión para re-lanzar sin reconfigurar
$script:lastDbConfig = $null
$script:lastDevJwtSecret = $null

# ═══════════════════════════════════════════════════════════════════
# UTILIDADES COMPARTIDAS
# ═══════════════════════════════════════════════════════════════════
function Write-Log {
    param([string]$Message, [string]$Level = 'INFO')
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    Add-Content -Path $LogFile -Value "[$timestamp] [$Level] $Message" -Encoding UTF8
    switch ($Level) {
        'ERROR'   { Write-Host "  [X] $Message" -ForegroundColor Red }
        'WARN'    { Write-Host "  [!] $Message" -ForegroundColor Yellow }
        'SUCCESS' { Write-Host "  [OK] $Message" -ForegroundColor Green }
        'STEP'    { Write-Host "`n>> $Message" -ForegroundColor Cyan }
        default   { Write-Host "  $Message" }
    }
}

function Write-Banner {
    param([string]$Subtitle = '')
    Write-Host ""
    Write-Host "======================================================================" -ForegroundColor Cyan
    Write-Host "     CONTROL EAT FOOD $Subtitle" -ForegroundColor Cyan
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
        $sel = Read-Host "  $Prompt"
        if ($sel -match '^\d+$' -and [int]$sel -ge $Min -and [int]$sel -le $Max) { return [int]$sel }
        Write-Host "    Opcion no valida. Ingrese un numero entre $Min y $Max." -ForegroundColor Red
    } while ($true)
}

function Read-Default {
    param([string]$Prompt, [string]$Default)
    $val = Read-Host "  $Prompt [$Default]"
    if ([string]::IsNullOrWhiteSpace($val)) { return $Default }
    return $val.Trim()
}

function Read-SecureInput {
    param([string]$Prompt)
    $sec = Read-Host "  $Prompt" -AsSecureString
    $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
    return [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
}

function Read-RequiredInput {
    # A diferencia de Read-Default, no acepta ENTER en blanco: repite el
    # prompt hasta que el usuario ingrese un valor. Se usa para la contrasena
    # al crear la base de datos desde cero, donde no debe quedar un default
    # debil silencioso.
    param([string]$Prompt)
    do {
        $val = Read-Host "  $Prompt"
        if ([string]::IsNullOrWhiteSpace($val)) {
            Write-Host "    Este campo es obligatorio, no puede quedar vacio." -ForegroundColor Red
        }
    } while ([string]::IsNullOrWhiteSpace($val))
    return $val.Trim()
}

function Test-CommandExists {
    param([string]$Command)
    $null -ne (Get-Command $Command -ErrorAction SilentlyContinue)
}

function Test-TcpPort {
    # Connect() sincrono puede colgarse 20-60+ segundos si el host no responde
    # (firewall que descarta paquetes en silencio, tipico en servidores remotos).
    # BeginConnect + WaitOne acota la espera a $TimeoutMs para poder reflejar
    # el error rapido en vez de congelar el script.
    param([string]$HostName, [int]$Port, [int]$TimeoutMs = 5000)
    try {
        $tcp = New-Object System.Net.Sockets.TcpClient
        $iar = $tcp.BeginConnect($HostName, $Port, $null, $null)
        $connected = $iar.AsyncWaitHandle.WaitOne($TimeoutMs, $false)
        if (-not $connected) { $tcp.Close(); return $false }
        $tcp.EndConnect($iar)
        $tcp.Close()
        return $true
    } catch { return $false }
}

function Generate-RandomBase64 {
    param([int]$Bytes = 32)
    $rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
    $buf = New-Object byte[] $Bytes
    $rng.GetBytes($buf)
    return [Convert]::ToBase64String($buf)
}

function Get-DevJwtSecret {
    # Secreto de JWT para el modo Pruebas: se genera una sola vez y se persiste en
    # RunWindowns/config/ (ya excluido en .gitignore), en vez de quedar como literal
    # fijo en este script. Evita reutilizar el mismo secreto en cada maquina de
    # desarrollo y que quede versionado por error.
    $secretFile = Join-Path $ConfigDir "dev_jwt_secret.txt"
    if (Test-Path $secretFile) {
        return (Get-Content $secretFile -Raw).Trim()
    }
    $secret = Generate-RandomBase64 -Bytes 32
    Set-Content -Path $secretFile -Value $secret -Encoding ASCII -NoNewline
    return $secret
}

function Get-ServerIP {
    $ip = (Get-NetIPAddress -AddressFamily IPv4 |
        Where-Object { $_.IPAddress -ne '127.0.0.1' -and $_.PrefixOrigin -ne 'WellKnown' } |
        Select-Object -First 1).IPAddress
    if (-not $ip) { $ip = 'localhost' }
    return $ip
}

function Test-IsAdmin {
    $principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Update-SessionPath {
    # Un winget install modifica el PATH en el registro (Machine/User), pero la
    # sesion de PowerShell actual no lo relee sola. Sin esto, java/npm/mvn
    # recien instalados seguirian dando "no reconocido" en la misma corrida.
    $machinePath = [System.Environment]::GetEnvironmentVariable("Path", "Machine")
    $userPath = [System.Environment]::GetEnvironmentVariable("Path", "User")
    $env:Path = "$machinePath;$userPath"
}

# ═══════════════════════════════════════════════════════════════════
# PREREQUISITOS (compartido por Pruebas y Produccion)
# ═══════════════════════════════════════════════════════════════════
function Test-Java21 {
    Write-Log "Verificando Java 21..."
    try {
        $javaVer = & java -version 2>&1 | Select-String -Pattern '"(\d+)' | ForEach-Object { $_.Matches[0].Groups[1].Value }
        if ($javaVer -eq '21') { Write-Log "Java 21 encontrado." 'SUCCESS'; return $true }
        throw "Java 21 no encontrado"
    } catch {
        Write-Log "Java 21 no esta instalado. Instalando via winget..." 'WARN'
        try {
            & winget install EclipseAdoptium.Temurin.21.JDK --accept-package-agreements --accept-source-agreements | Out-Null
            if ($LASTEXITCODE -eq 0) { Update-SessionPath; Write-Log "Java 21 instalado correctamente." 'SUCCESS'; return $true }
            throw "winget fallo"
        } catch {
            Write-Log "No se pudo instalar Java automaticamente." 'ERROR'
            Start-Process "https://adoptium.net/temurin/releases/?version=21"
            Read-Host "  Presione ENTER despues de instalar Java 21"
            return $false
        }
    }
}

function Test-NodeInstalled {
    Write-Log "Verificando Node.js y NPM..."
    if (Test-CommandExists 'npm') {
        $npmVer = & npm --version 2>&1
        Write-Log "Node.js/NPM encontrado (npm v$npmVer)." 'SUCCESS'
        return $true
    }
    Write-Log "Node.js no esta instalado. Instalando via winget..." 'WARN'
    try {
        & winget install OpenJS.NodeJS.LTS --accept-package-agreements --accept-source-agreements | Out-Null
        if ($LASTEXITCODE -eq 0) { Update-SessionPath; Write-Log "Node.js instalado correctamente." 'SUCCESS'; return $true }
        throw "winget fallo"
    } catch {
        Write-Log "No se pudo instalar Node.js automaticamente." 'ERROR'
        Start-Process "https://nodejs.org/"
        Read-Host "  Presione ENTER despues de instalar Node.js"
        return $false
    }
}

function Install-MavenViaWinget {
    # Instala Maven exclusivamente por linea de comandos (winget), sin abrir
    # el navegador ni pedir pasos manuales. Reintenta un par de veces por si
    # el primer intento falla por caches/agreements pendientes, y refresca el
    # PATH de la sesion despues de cada intento antes de verificar "mvn".
    $maxAttempts = 3
    for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
        Write-Log "Instalando Maven via winget (intento $attempt de $maxAttempts)..."
        & winget install --id Apache.Maven -e --accept-package-agreements --accept-source-agreements --force | Out-Null
        $wingetExit = $LASTEXITCODE
        Update-SessionPath
        if (Test-CommandExists 'mvn') {
            $mvnVer = & mvn --version 2>&1 | Select-String -Pattern 'Apache Maven' | ForEach-Object { $_.Line }
            Write-Log "Maven instalado correctamente ($mvnVer)." 'SUCCESS'
            return $true
        }
        $wingetExitHex = "0x{0:X8}" -f ([int64]$wingetExit -band 0xFFFFFFFFL)
        Write-Log "winget termino (codigo $wingetExit / $wingetExitHex) pero 'mvn' aun no responde en esta sesion." 'WARN'
        if ($attempt -lt $maxAttempts) { Start-Sleep -Seconds 3 }
    }
    return $false
}

function Find-MavenHomeUnder {
    # Busca recursivamente bin\mvn.cmd y devuelve su carpeta padre (el
    # MAVEN_HOME real). No asumimos que Expand-Archive deja el contenido a un
    # solo nivel: por seguridad, buscamos sin importar cuantas carpetas
    # intermedias haya (defensivo ante duplicados de carpeta al extraer).
    param([string]$RootDir)
    $mvnCmd = Get-ChildItem -Path $RootDir -Recurse -Filter "mvn.cmd" -ErrorAction SilentlyContinue |
        Where-Object { (Split-Path -Leaf $_.DirectoryName) -eq 'bin' } |
        Select-Object -First 1
    if ($mvnCmd) { return (Split-Path -Parent $mvnCmd.DirectoryName) }
    return $null
}

function Install-MavenPortable {
    # Descarga el ZIP binario oficial de Maven directo del CDN de Apache y lo
    # instala de forma portable en RunWindowns\tools\maven, sin depender de
    # winget ni de ningun gestor de paquetes de terceros. No requiere
    # Administrador (variables de PATH a nivel de usuario). Al terminar borra
    # el ZIP descargado y cualquier carpeta intermedia sobrante, para no
    # dejar residuos en el proyecto.
    $mavenVersion = "3.9.16"
    $zipUrl = "https://dlcdn.apache.org/maven/maven-3/$mavenVersion/binaries/apache-maven-$mavenVersion-bin.zip"
    $mavenRoot = Join-Path $NssmDir "maven"
    $zipPath = Join-Path $mavenRoot "apache-maven-$mavenVersion-bin.zip"
    $stagingDir = Join-Path $mavenRoot "_staging"

    try {
        New-Item -ItemType Directory -Force -Path $mavenRoot | Out-Null
        if (Test-Path $stagingDir) { Remove-Item $stagingDir -Recurse -Force -ErrorAction SilentlyContinue }

        Write-Log "Descargando Maven $mavenVersion desde $zipUrl ..."
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri $zipUrl -OutFile $zipPath -UseBasicParsing -ErrorAction Stop

        Write-Log "Descomprimiendo Maven..."
        Expand-Archive -Path $zipPath -DestinationPath $stagingDir -Force -ErrorAction Stop
    } catch {
        Write-Log "No se pudo descargar/descomprimir Maven: $_" 'ERROR'
        Remove-Item $zipPath -Force -ErrorAction SilentlyContinue
        Remove-Item $stagingDir -Recurse -Force -ErrorAction SilentlyContinue
        return $false
    }

    # La direccion se "duplicaria" si asumimos un nivel fijo de carpetas; en
    # vez de eso, ubicamos el bin\mvn.cmd real sin importar la profundidad y
    # movemos SOLO esa carpeta (apache-maven-3.9.16) al destino final.
    $found = Find-MavenHomeUnder -RootDir $stagingDir
    if (-not $found) {
        Write-Log "Se descomprimio el ZIP pero no se encontro bin\mvn.cmd dentro." 'ERROR'
        Remove-Item $zipPath -Force -ErrorAction SilentlyContinue
        Remove-Item $stagingDir -Recurse -Force -ErrorAction SilentlyContinue
        return $false
    }

    $finalDest = Join-Path $mavenRoot (Split-Path -Leaf $found)
    if (Test-Path $finalDest) { Remove-Item $finalDest -Recurse -Force -ErrorAction SilentlyContinue }
    Move-Item -Path $found -Destination $finalDest -Force

    # Limpieza: borra el ZIP y la carpeta de staging (con cualquier wrapper
    # intermedio que haya quedado vacio tras mover la carpeta real), para que
    # RunWindowns\tools\maven solo contenga la instalacion final.
    Remove-Item $zipPath -Force -ErrorAction SilentlyContinue
    Remove-Item $stagingDir -Recurse -Force -ErrorAction SilentlyContinue

    $mavenHome = Get-Item $finalDest
    Write-Log "Configurando MAVEN_HOME y PATH (variables de usuario, sin requerir Administrador)..."
    $binPath = Join-Path $mavenHome.FullName "bin"
    [Environment]::SetEnvironmentVariable('MAVEN_HOME', $mavenHome.FullName, 'User')
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    if (-not $userPath) { $userPath = '' }
    if ($userPath -notlike "*$binPath*") {
        [Environment]::SetEnvironmentVariable('Path', "$userPath;$binPath", 'User')
    }
    Update-SessionPath
    $env:MAVEN_HOME = $mavenHome.FullName

    if (Test-CommandExists 'mvn') {
        $mvnVer = & mvn --version 2>&1 | Select-String -Pattern 'Apache Maven' | ForEach-Object { $_.Line }
        Write-Log "Maven $mavenVersion instalado correctamente en $($mavenHome.FullName) ($mvnVer)." 'SUCCESS'
        return $true
    }
    Write-Log "Maven se instalo en $($mavenHome.FullName) pero 'mvn' no responde en esta sesion." 'ERROR'
    return $false
}

function Test-MavenInstalled {
    Write-Log "Verificando Maven..."
    if (Test-CommandExists 'mvn') {
        $mvnVer = & mvn --version 2>&1 | Select-String -Pattern 'Apache Maven' | ForEach-Object { $_.Line }
        Write-Log "Maven encontrado ($mvnVer)." 'SUCCESS'
        return $true
    }
    Write-Log "Maven no esta instalado. Se instalara por linea de comandos (winget), sin pasos manuales." 'WARN'
    if (Install-MavenViaWinget) { return $true }

    Write-Log "winget no pudo instalar Maven. Descargando el ZIP portable oficial como alternativa..." 'WARN'
    if (Install-MavenPortable) { return $true }

    Write-Log "No se pudo instalar Maven ni con winget ni con la descarga portable." 'ERROR'
    Write-Host ""
    Write-Host "  Verifica que haya conexion a internet (se necesita para winget y" -ForegroundColor Yellow
    Write-Host "  para descargar el ZIP de Maven desde dlcdn.apache.org)." -ForegroundColor Yellow
    $retry = Read-Host "  Presiona ENTER para reintentar, o escribe 'n' para omitir"
    if ($retry -ne 'n') {
        if (Install-MavenViaWinget) { return $true }
        if (Install-MavenPortable) { return $true }
    }
    Write-Log "Maven no quedo instalado. mvn no estara disponible hasta resolverlo y reintentar." 'ERROR'
    return $false
}

# ═══════════════════════════════════════════════════════════════════
# BASE DE DATOS (compartido por Pruebas y Produccion)
# Dev  -> pregunta Local (Docker/MySQL local, auto-detect) o Remoto
# Prod -> siempre remoto: se conecta directo a un servidor, sin Docker
# ═══════════════════════════════════════════════════════════════════
function Step-ConfigureDatabase {
    param([ValidateSet('Dev', 'Prod')][string]$Mode)

    Write-Log "Configurar conexion a base de datos ($Mode)..." 'STEP'

    $dbConfig = @{
        Type = ''; Host = 'localhost'; Port = '3306'
        Name = 'control_almuerzos'; User = 'admin'; Password = ''; Url = ''
    }

    $choice = 2
    if ($Mode -eq 'Dev') {
        Show-Menu "A que base de datos te vas a conectar?" @(
            "Local (detecta Docker o MySQL local automaticamente)",
            "Servidor remoto (ingresas host, usuario y password)"
        )
        $choice = Read-Choice "Seleccionar opcion" -Max 2
    } else {
        Write-Log "Produccion se conecta directo a un servidor MySQL remoto (no usa Docker)." 'INFO'
    }

    if ($choice -eq 1) {
        # ============ LOCAL (solo disponible en modo Pruebas) ============
        $dbConfig.Type = 'local'

        if (Test-TcpPort -HostName 'localhost' -Port 3306) {
            Write-Log "Se detecto un servicio en el puerto 3306 (MySQL ya esta corriendo)." 'SUCCESS'
            $containerRunning = $false
            try {
                $containers = & docker ps --format "{{.Names}}" 2>&1
                if ($containers -match 'control-mysql') { $containerRunning = $true }
            } catch { }
            if ($containerRunning) {
                Write-Log "El contenedor Docker 'control-mysql' esta en ejecucion." 'SUCCESS'
            } else {
                Write-Log "Asumiendo que MySQL esta corriendo de forma local. Verifica que la BD y el usuario existan." 'WARN'
            }
            Write-Host "    Usuario: admin (fijo)" -ForegroundColor DarkGray
            $dbConfig.Password = Read-RequiredInput "Contrasena para el usuario 'admin' de MySQL (obligatoria)"
        } else {
            Show-Menu "Como deseas levantar MySQL local?" @(
                "Docker (contenedor control-mysql)",
                "MySQL instalado localmente (requiere mysql en PATH)",
                "Omitir (lo configuro despues)"
            )
            $mysqlChoice = Read-Choice "Seleccionar opcion" -Max 3
            switch ($mysqlChoice) {
                1 {
                    if (-not (Test-CommandExists 'docker')) {
                        Write-Log "Docker no esta instalado." 'ERROR'
                        Start-Process "https://www.docker.com/products/docker-desktop/"
                        Read-Host "  Presione ENTER cuando Docker este instalado"
                    }
                    Write-Host "    Usuario: admin (fijo)" -ForegroundColor DarkGray
                    $dbConfig.Password = Read-RequiredInput "Contrasena para el usuario 'admin' de MySQL (obligatoria)"

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
                            -e "MYSQL_ROOT_PASSWORD=$($dbConfig.Password)" `
                            -e "MYSQL_DATABASE=$($dbConfig.Name)" `
                            -e "MYSQL_USER=$($dbConfig.User)" `
                            -e "MYSQL_PASSWORD=$($dbConfig.Password)" `
                            -d mysql:8.0 2>&1 | Out-Null
                        if ($LASTEXITCODE -eq 0) { Write-Log "Contenedor Docker creado e iniciado." 'SUCCESS' }
                        else { Write-Log "Error al crear contenedor Docker." 'ERROR' }
                    }
                }
                2 {
                    if (-not (Test-CommandExists 'mysql')) {
                        Write-Log "'mysql' no encontrado en PATH." 'ERROR'
                        Read-Host "  Presione ENTER cuando MySQL este configurado"
                    } else {
                        Write-Host "    Usuario: admin (fijo)" -ForegroundColor DarkGray
                        $dbConfig.Password = Read-RequiredInput "Contrasena para el usuario 'admin' de MySQL (obligatoria)"
                        $rootPass = Read-Host "  Contrasena de root en MySQL local (ENTER para omitir)"
                        if ($rootPass) {
                            & mysql -u root -p"$rootPass" -e "CREATE DATABASE IF NOT EXISTS $($dbConfig.Name) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER IF NOT EXISTS '$($dbConfig.User)'@'localhost' IDENTIFIED BY '$($dbConfig.Password)'; GRANT ALL PRIVILEGES ON $($dbConfig.Name).* TO '$($dbConfig.User)'@'localhost'; FLUSH PRIVILEGES;" 2>&1 | Out-Null
                            if ($LASTEXITCODE -eq 0) { Write-Log "Base de datos y usuario configurados localmente." 'SUCCESS' }
                            else { Write-Log "Error al configurar MySQL local." 'ERROR' }
                        }
                    }
                }
                3 { Write-Log "Configuracion de DB omitida. Configurala manualmente." 'WARN' }
            }
        }
        $dbConfig.Url = "jdbc:mysql://localhost:$($dbConfig.Port)/$($dbConfig.Name)?createDatabaseIfNotExist=true&serverTimezone=America/Guayaquil"
    }
    else {
        # ============ REMOTO (Pruebas -> remoto, o siempre en Produccion) ============
        $dbConfig.Type = 'remote'
        Write-Log "Configuracion de base de datos remota..."

        $dbConfig.Host = ''
        $dbConfig.Name = ''
        $dbConfig.User = ''
        $dbConfig.Password = ''

        $connSuccess = $false
        do {
            $dbConfig.Host = if ($dbConfig.Host) { Read-Default "IP/Host del servidor MySQL" $dbConfig.Host } else { Read-RequiredInput "IP/Host del servidor MySQL" }
            $dbConfig.Port = Read-Default "Puerto" $dbConfig.Port
            $dbConfig.Name = if ($dbConfig.Name) { Read-Default "Nombre de base de datos" $dbConfig.Name } else { Read-RequiredInput "Nombre de base de datos" }
            $dbConfig.User = if ($dbConfig.User) { Read-Default "Usuario" $dbConfig.User } else { Read-RequiredInput "Usuario" }
            
            if ($Mode -eq 'Prod') {
                if ($dbConfig.Password) {
                    $newPass = Read-SecureInput "Contrasena (ENTER para mantener actual)"
                    if (-not [string]::IsNullOrWhiteSpace($newPass)) { $dbConfig.Password = $newPass }
                } else {
                    $dbConfig.Password = Read-SecureInput "Contrasena"
                }
            } else {
                $dbConfig.Password = if ($dbConfig.Password) { Read-Default "Contrasena" $dbConfig.Password } else { Read-RequiredInput "Contrasena" }
            }

            Write-Host ""
            Write-Host "  --- Resumen de configuracion ---" -ForegroundColor Cyan
            Write-Host "    Host: $($dbConfig.Host):$($dbConfig.Port)"
            Write-Host "    Base de datos: $($dbConfig.Name)"
            Write-Host "    Usuario: $($dbConfig.User)"
            Write-Host ""
            
            $confirm = Read-Host "  Los datos son correctos? (s/n, ENTER para 's')"
            if ($confirm -eq 'n') {
                continue
            }

            Write-Log "Probando conexion a $($dbConfig.Host):$($dbConfig.Port)..."
            $connSuccess = Test-TcpPort -HostName $dbConfig.Host -Port ([int]$dbConfig.Port)
            if ($connSuccess) {
                Write-Log "Conexion TCP exitosa a $($dbConfig.Host):$($dbConfig.Port)." 'SUCCESS'
            } else {
                Write-Log "No se pudo conectar a $($dbConfig.Host):$($dbConfig.Port)." 'ERROR'
                Write-Log "Verifica firewall, que MySQL acepte conexiones remotas, y las credenciales." 'WARN'
                
                $retry = Read-Host "  La conexion TCP fallo. Reintentar configuracion? (s/n, n para continuar de todos modos)"
                if ($retry -eq 'n') {
                    $connSuccess = $true
                }
            }
        } until ($connSuccess)

        $dbConfig.Url = "jdbc:mysql://$($dbConfig.Host):$($dbConfig.Port)/$($dbConfig.Name)?useSSL=false&serverTimezone=America/Guayaquil&allowPublicKeyRetrieval=true"
    }

    Write-Log "DB_URL = $($dbConfig.Url)" 'SUCCESS'
    return $dbConfig
}

# ═══════════════════════════════════════════════════════════════════
# FRONTEND / SDK BIOMETRICO (compartido)
# ═══════════════════════════════════════════════════════════════════
function Install-FrontendDependencies {
    Write-Log "Instalando dependencias del frontend..." 'STEP'
    if (-not (Test-Path (Join-Path $FrontendDir "package.json"))) {
        Write-Log "No se encontro package.json en $FrontendDir" 'ERROR'
        return $false
    }
    Push-Location $FrontendDir
    try {
        # No basta con Test-Path "node_modules": una instalacion interrumpida
        # (ej. Ctrl+C, sin espacio en disco, antivirus) deja la carpeta creada
        # pero incompleta -- sin node_modules\.bin\vite.cmd -- y "npm run dev"
        # falla con "'vite' is not recognized" aunque node_modules ya exista.
        $viteBin = Join-Path "node_modules\.bin" "vite.cmd"
        if ((Test-Path "node_modules") -and (Test-Path $viteBin)) {
            Write-Log "node_modules ya existe y esta completo (vite presente)." 'SUCCESS'
        } else {
            if (Test-Path "node_modules") {
                Write-Log "node_modules existe pero esta incompleto (falta vite). Reinstalando..." 'WARN'
            }
            Write-Log "Ejecutando npm install (puede tardar)..."
            & npm install 2>&1 | ForEach-Object { Write-Log $_ }
            if ($LASTEXITCODE -ne 0) {
                Write-Log "npm install fallo (codigo $LASTEXITCODE)." 'ERROR'
                return $false
            }
            if (-not (Test-Path $viteBin)) {
                Write-Log "npm install termino pero vite sigue sin aparecer en node_modules\.bin." 'ERROR'
                return $false
            }
            Write-Log "Dependencias instaladas." 'SUCCESS'
        }
        if (-not (Test-Path "node_modules\qrcode")) {
            Write-Log "Instalando dependencia adicional: qrcode..."
            & npm install qrcode 2>&1 | Out-Null
            if ($LASTEXITCODE -ne 0) {
                Write-Log "No se pudo instalar qrcode (codigo $LASTEXITCODE)." 'ERROR'
                return $false
            }
            Write-Log "qrcode instalado." 'SUCCESS'
        } else {
            Write-Log "qrcode ya esta instalado." 'SUCCESS'
        }
        return $true
    } finally {
        Pop-Location
    }
}

function Invoke-BiometricSetup {
    Write-Log "Verificando setup.exe (SDK biometrico)..." 'STEP'
    if (Test-Path $LockFile) {
        Write-Log "setup.exe ya fue ejecutado previamente en esta maquina." 'SUCCESS'
        return
    }
    if (-not (Test-Path $SetupExe)) {
        Write-Log "setup.exe no encontrado en $ScriptRoot" 'WARN'
        return
    }
    Write-Log "Ejecutando setup.exe para instalar el SDK del lector biometrico..."
    Start-Process -FilePath $SetupExe -Wait
    "OK" | Set-Content -Path $LockFile -Encoding ASCII
    Write-Log "setup.exe finalizado." 'SUCCESS'
}

# ═══════════════════════════════════════════════════════════════════
# MODO PRUEBAS (equivalente a setup_env.bat, mejorado)
# ═══════════════════════════════════════════════════════════════════
function Stop-ExistingProcesses {
    Write-Log "Cerrando procesos anteriores del backend/frontend..." 'STEP'
    
    # Cerrar ventanas de PowerShell con el título específico
    $shell = New-Object -ComObject Shell.Application
    $windows = $shell.Windows()
    foreach ($window in $windows) {
        if ($window.LocationName -match 'ControlEatFood') {
            $window.Quit()
            Start-Sleep -Milliseconds 500
        }
    }
    
    # Matar procesos de Java (backend) que estén usando el puerto 3000
    $javaProcesses = Get-Process -Name java -ErrorAction SilentlyContinue
    if ($javaProcesses) {
        foreach ($proc in $javaProcesses) {
            try {
                $connections = Get-NetTCPConnection -OwningProcess $proc.Id -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -eq 3000 }
                if ($connections) {
                    $proc | Stop-Process -Force -ErrorAction SilentlyContinue
                    Write-Log "Proceso backend cerrado (PID: $($proc.Id))." 'SUCCESS'
                }
            } catch { }
        }
    }
    
    # Matar procesos de Node (frontend) que estén usando el puerto 5173
    $nodeProcesses = Get-Process -Name node -ErrorAction SilentlyContinue
    if ($nodeProcesses) {
        foreach ($proc in $nodeProcesses) {
            try {
                $connections = Get-NetTCPConnection -OwningProcess $proc.Id -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -eq 5173 }
                if ($connections) {
                    $proc | Stop-Process -Force -ErrorAction SilentlyContinue
                    Write-Log "Proceso frontend cerrado (PID: $($proc.Id))." 'SUCCESS'
                }
            } catch { }
        }
    }
    
    Start-Sleep -Seconds 1
}

function Start-TestSetup {
    Clear-Host
    Write-Banner "- MODO PRUEBAS (Desarrollo)"

    Write-Log "Verificando prerequisitos (no se reinstala nada que ya este presente)..." 'STEP'
    $javaOk = Test-Java21
    $nodeOk = Test-NodeInstalled
    $mvnOk = Test-MavenInstalled
    Write-Host ""
    Write-Host "  Resumen prerequisitos:" -ForegroundColor Yellow
    Write-Host "    Java 21:  $(if($javaOk){'[OK] ya estaba instalado o se instalo ahora'}else{'[FALLO]'})"
    Write-Host "    Node.js:  $(if($nodeOk){'[OK] ya estaba instalado o se instalo ahora'}else{'[FALLO]'})"
    Write-Host "    Maven:    $(if($mvnOk){'[OK] ya estaba instalado o se instalo ahora'}else{'[FALLO]'})"
    Write-Host ""

    $dbConfig = Step-ConfigureDatabase -Mode 'Dev'
    if (-not (Install-FrontendDependencies)) {
        Write-Log "No se pudieron instalar las dependencias del frontend. Revisa el log." 'WARN'
    }
    Invoke-BiometricSetup
    
    # Guardar configuración en variable de sesión para re-lanzar después
    $script:lastDbConfig = $dbConfig
    $script:lastDevJwtSecret = Get-DevJwtSecret

    Write-Host ""
    Write-Host "  ============================================================" -ForegroundColor Green
    Write-Host "  ENTORNO DE PRUEBAS LISTO" -ForegroundColor Green
    Write-Host "  ============================================================" -ForegroundColor Green
    Write-Host "  BD:       $($dbConfig.Url)" -ForegroundColor Cyan
    Write-Host "  Usuario:  $($dbConfig.User)" -ForegroundColor Cyan
    Write-Host ""

    $devJwtSecret = Get-DevJwtSecret

    Show-Menu "Que deseas hacer?" @(
        "Iniciar backend y frontend en ventanas separadas",
        "Volver a configurar base de datos",
        "Volver al menu principal"
    )
    $action = Read-Choice "Seleccionar" -Max 3

    switch ($action) {
        1 {
            Stop-ExistingProcesses
            $beCmd = "cd `"$BackendDir`"; " +
                "`$env:DB_URL='$($dbConfig.Url)'; `$env:DB_USER='$($dbConfig.User)'; `$env:DB_PASSWORD='$($dbConfig.Password)'; " +
                "`$env:JWT_SECRET='$devJwtSecret'; " +
                "`$env:CORS_ORIGINS='http://localhost:5173,http://localhost:5174,http://localhost:4173'; " +
                "`$env:RATE_LIMIT_ENABLED='true'; `$env:RATE_LIMIT_AUTH='10'; `$env:RATE_LIMIT_SCAN='60'; " +
                "`$env:ZK_NATIVE_PATH='./native'; " +
                "mvn spring-boot:run"
            Start-Process powershell -ArgumentList "-NoExit", "-Command", $beCmd
            Start-Sleep -Seconds 2
            Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd `"$FrontendDir`"; npm run dev"
            Write-Log "Backend y frontend lanzados en ventanas nuevas." 'SUCCESS'
            Write-Host ""
            Read-Host "  Presione ENTER para volver al menu"
        }
        2 {
            Start-TestSetup
        }
        3 {
            # Volver al menú principal
            return
        }
    }
}

# ═══════════════════════════════════════════════════════════════════
# MODO PRODUCCION (equivalente a install.ps1 + uninstall.ps1)
# ═══════════════════════════════════════════════════════════════════
function Step-ConfigureProduction {
    $prodConfig = @{
        JwtSecret = ''; CorsOrigins = ''; PublicUrl = ''; BiometricEncryptionKey = ''
        RateLimitEnabled = 'true'; RateLimitAuth = '10'; RateLimitScan = '60'
        BackendPort = '8080'; ServerIP = (Get-ServerIP)
    }

    Write-Host ""
    Write-Host "  --- JWT Secret ---" -ForegroundColor Yellow
    Write-Host "    [1] Generar automaticamente (recomendado)"
    Write-Host "    [2] Ingresar manualmente"
    $prodConfig.JwtSecret = if ((Read-Choice "Seleccionar" -Max 2) -eq 1) {
        Write-Log "JWT Secret generado automaticamente." 'SUCCESS'
        Generate-RandomBase64 -Bytes 32
    } else { Read-Host "  Ingrese JWT Secret (Base64, min 256 bits)" }

    Write-Host ""
    Write-Host "  --- CORS Origins ---" -ForegroundColor Yellow
    $defaultCors = "http://$($prodConfig.ServerIP),http://localhost"
    Write-Host "    IP detectada del servidor: $($prodConfig.ServerIP)"
    $prodConfig.CorsOrigins = Read-Default "Origenes CORS (separar por coma)" $defaultCors

    Write-Host ""
    Write-Host "  --- URL Publica ---" -ForegroundColor Yellow
    Write-Host "    [1] Auto-detectar (vacio)"
    Write-Host "    [2] Ingresar manualmente (ej: https://catering.empresa.com)"
    if ((Read-Choice "Seleccionar" -Max 2) -eq 2) {
        $prodConfig.PublicUrl = Read-Host "  URL publica"
    }

    Write-Host ""
    Write-Host "  --- Biometric Encryption Key (AES) ---" -ForegroundColor Yellow
    Write-Host "    [1] Generar automaticamente (recomendado)"
    Write-Host "    [2] Ingresar manualmente"
    Write-Host "    [3] Dejar vacio (no cifrar plantillas)"
    switch (Read-Choice "Seleccionar" -Max 3) {
        1 { $prodConfig.BiometricEncryptionKey = Generate-RandomBase64 -Bytes 16; Write-Log "Biometric Encryption Key generada." 'SUCCESS' }
        2 { $prodConfig.BiometricEncryptionKey = Read-Host "  Ingrese clave AES (min 16 bytes Base64)" }
        3 { $prodConfig.BiometricEncryptionKey = '' }
    }

    Write-Host ""
    Write-Host "  --- Rate Limit ---" -ForegroundColor Yellow
    Write-Host "    [1] Habilitado (recomendado para produccion)"
    Write-Host "    [2] Deshabilitado"
    $prodConfig.RateLimitEnabled = if ((Read-Choice "Seleccionar" -Max 2) -eq 1) { 'true' } else { 'false' }

    Write-Host ""
    Write-Host "  --- Puerto ---" -ForegroundColor Yellow
    $prodConfig.BackendPort = Read-Default "Puerto de la aplicacion (API + frontend)" "8080"

    return $prodConfig
}

function Step-BuildProduction {
    param($DbConfig, $ProdConfig)
    Write-Log "Generando build de produccion..." 'STEP'

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
    native-lib-path: "$ZkNativePath"
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

    Write-Log "Compilando frontend (npm run build)..."
    Push-Location $FrontendDir
    try {
        & npm run build 2>&1 | ForEach-Object { Write-Log $_ }
        if ($LASTEXITCODE -ne 0) { throw "npm run build fallo" }
        Write-Log "Frontend compilado (dist/ generado)." 'SUCCESS'
    } finally { Pop-Location }

    Write-Log "Integrando frontend en el backend (mismo origen, mismo puerto)..."
    $staticDir = Join-Path $BackendDir "src\main\resources\static"
    if (Test-Path $staticDir) {
        Remove-Item -Path (Join-Path $staticDir "*") -Recurse -Force -ErrorAction SilentlyContinue
    } else {
        New-Item -ItemType Directory -Path $staticDir -Force | Out-Null
    }
    Copy-Item -Path (Join-Path $FrontendDir "dist\*") -Destination $staticDir -Recurse -Force
    Write-Log "Frontend copiado a $staticDir." 'SUCCESS'

    Write-Log "Compilando backend (mvn clean package)..."
    Push-Location $BackendDir
    try {
        $targetResources = Join-Path $BackendDir "src\main\resources"
        Copy-Item $ProdYmlPath (Join-Path $targetResources "application-prod.yml") -Force

        & mvn clean package -DskipTests -Dspring.profiles.active=prod 2>&1 | ForEach-Object {
            if ($_ -match 'BUILD SUCCESS') { Write-Log $_ 'SUCCESS' }
            elseif ($_ -match 'BUILD FAILURE|ERROR') { Write-Log $_ 'ERROR' }
        }
        if ($LASTEXITCODE -ne 0) { throw "mvn package fallo" }

        $jarFile = Get-ChildItem (Join-Path $BackendDir "target") -Filter "*.jar" |
            Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
        if ($jarFile) { Write-Log "Backend compilado: $($jarFile.FullName)" 'SUCCESS' }
        else { Write-Log "No se encontro el JAR generado." 'ERROR' }
    } finally { Pop-Location }
}

function Step-ConfigureService {
    param($DbConfig, $ProdConfig)
    Write-Log "Configurando servicio de Windows..." 'STEP'

    $jarFile = Get-ChildItem (Join-Path $BackendDir "target") -Filter "*.jar" |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
    if (-not $jarFile) { Write-Log "No se encontro JAR del backend. No se puede crear servicio." 'ERROR'; return $false }

    if (-not (Test-Path $NssmExe)) {
        Write-Log "Descargando NSSM (Non-Sucking Service Manager)..."
        try {
            $nssmUrl = "https://nssm.cc/release/nssm-2.24.zip"
            $zipPath = Join-Path $NssmDir "nssm.zip"
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            Invoke-WebRequest -Uri $nssmUrl -OutFile $zipPath -UseBasicParsing -ErrorAction Stop
            Expand-Archive -Path $zipPath -DestinationPath $NssmDir -Force -ErrorAction Stop
            $foundNssm = Get-ChildItem $NssmDir -Recurse -Filter "nssm.exe" |
                Where-Object { $_.DirectoryName -match 'win64' } | Select-Object -First 1
            if (-not $foundNssm) { $foundNssm = Get-ChildItem $NssmDir -Recurse -Filter "nssm.exe" | Select-Object -First 1 }
            if ($foundNssm) { Copy-Item $foundNssm.FullName $NssmExe -Force; Write-Log "NSSM descargado: $NssmExe" 'SUCCESS' }
            Remove-Item $zipPath -Force -ErrorAction SilentlyContinue
        } catch {
            Write-Log "No se pudo descargar NSSM automaticamente." 'ERROR'
            Start-Process "https://nssm.cc/download"
            Read-Host "  Presione ENTER cuando NSSM este instalado en $NssmDir"
            if (-not (Test-Path $NssmExe)) { Write-Log "NSSM no encontrado. Servicio no creado." 'ERROR'; return $false }
        }
    }

    $existingService = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
    if ($existingService) {
        Write-Log "Servicio '$ServiceName' ya existe. Deteniendo y eliminando..."
        & $NssmExe stop $ServiceName confirm 2>&1 | Out-Null
        & $NssmExe remove $ServiceName confirm 2>&1 | Out-Null
        Start-Sleep -Seconds 2
    }

    Write-Log "Registrando servicio '$ServiceName'..."
    $javaExe = (Get-Command java).Source
    $jarPath = $jarFile.FullName

    & $NssmExe install $ServiceName $javaExe "-jar `"$jarPath`" --spring.profiles.active=prod --spring.config.additional-location=file:`"$ProdYmlPath`"" | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Log "Error al registrar servicio." 'ERROR'; return $false }

    & $NssmExe set $ServiceName AppEnvironmentExtra "DB_URL=$($DbConfig.Url)" "DB_USER=$($DbConfig.User)" "DB_PASSWORD=$($DbConfig.Password)" "JWT_SECRET=$($ProdConfig.JwtSecret)" "CORS_ORIGINS=$($ProdConfig.CorsOrigins)" "PUBLIC_URL=$($ProdConfig.PublicUrl)" "BIOMETRIC_ENCRYPTION_KEY=$($ProdConfig.BiometricEncryptionKey)" "RATE_LIMIT_ENABLED=$($ProdConfig.RateLimitEnabled)" "ZK_NATIVE_PATH=$ZkNativePath" | Out-Null
    & $NssmExe set $ServiceName AppDirectory $BackendDir | Out-Null
    & $NssmExe set $ServiceName AppExit Default Restart | Out-Null
    & $NssmExe set $ServiceName AppRestartDelay 10000 | Out-Null
    & $NssmExe set $ServiceName Description "ControlEatFood - Backend Spring Boot (Produccion)" | Out-Null
    & $NssmExe set $ServiceName DisplayName "Control Eat Food" | Out-Null
    & $NssmExe set $ServiceName Start SERVICE_AUTO_START | Out-Null

    $serviceLogsDir = Join-Path $LogsDir "service"
    if (-not (Test-Path $serviceLogsDir)) { New-Item -ItemType Directory -Path $serviceLogsDir -Force | Out-Null }
    & $NssmExe set $ServiceName AppStdout (Join-Path $serviceLogsDir "stdout.log") | Out-Null
    & $NssmExe set $ServiceName AppStderr (Join-Path $serviceLogsDir "stderr.log") | Out-Null
    & $NssmExe set $ServiceName AppStderrCreationDisposition 4 | Out-Null
    & $NssmExe set $ServiceName AppStdoutCreationDisposition 4 | Out-Null

    Write-Log "Iniciando servicio '$ServiceName'..."
    & $NssmExe start $ServiceName | Out-Null
    Start-Sleep -Seconds 5

    $status = & $NssmExe status $ServiceName 2>&1
    if ($status -match 'SERVICE_RUNNING') { Write-Log "Servicio '$ServiceName' iniciado correctamente." 'SUCCESS' }
    else { Write-Log "El servicio podria no haber iniciado. Estado: $status" 'WARN'; Write-Log "Revisar logs en: $serviceLogsDir" 'WARN' }

    return $true
}

function Step-ConfigureFirewall {
    param($ProdConfig)
    Write-Log "Configurando firewall de Windows..." 'STEP'
    $ruleName = "ControlEatFood (Port $($ProdConfig.BackendPort))"
    if (Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue) {
        Write-Log "Regla de firewall ya existe." 'SUCCESS'
    } else {
        try {
            New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Protocol TCP -LocalPort $ProdConfig.BackendPort -Action Allow -Profile Any -ErrorAction Stop | Out-Null
            Write-Log "Regla de firewall creada: $ruleName" 'SUCCESS'
        } catch { Write-Log "No se pudo crear regla de firewall." 'ERROR' }
    }
}

function Save-InstallConfig {
    param($DbConfig, $ProdConfig)
    Write-Log "Guardando configuracion..." 'STEP'
    $config = @{
        installDate = (Get-Date -Format "yyyy-MM-dd HH:mm:ss")
        serverIP    = $ProdConfig.ServerIP
        database    = $DbConfig
        production  = @{
            jwtSecret = $ProdConfig.JwtSecret; corsOrigins = $ProdConfig.CorsOrigins
            publicUrl = $ProdConfig.PublicUrl; biometricEncryptionKey = $ProdConfig.BiometricEncryptionKey
            rateLimitEnabled = $ProdConfig.RateLimitEnabled; backendPort = $ProdConfig.BackendPort
        }
        service     = @{ name = $ServiceName; status = "installed" }
        paths       = @{ projectRoot = $ProjectRoot; backendDir = $BackendDir; frontendDir = $FrontendDir; prodYml = $ProdYmlPath; nssmExe = $NssmExe }
    }
    $config | ConvertTo-Json -Depth 5 | Set-Content -Path $ConfigFile -Encoding UTF8
    Write-Log "Configuracion guardada en: $ConfigFile" 'SUCCESS'
}

function Install-Full {
    Write-Host ""
    Write-Host "  ============================================================" -ForegroundColor Green
    Write-Host "  INICIANDO INSTALACION DE PRODUCCION" -ForegroundColor Green
    Write-Host "  ============================================================" -ForegroundColor Green

    $javaOk = Test-Java21
    $nodeOk = Test-NodeInstalled
    $mvnOk = Test-MavenInstalled
    Write-Host ""
    Write-Host "  Resumen prerequisitos:" -ForegroundColor Yellow
    Write-Host "    Java 21:  $(if($javaOk){'[OK]'}else{'[FALLO]'})"
    Write-Host "    Node.js:  $(if($nodeOk){'[OK]'}else{'[FALLO]'})"
    Write-Host "    Maven:    $(if($mvnOk){'[OK]'}else{'[FALLO]'})"
    Write-Host ""

    if ((Read-Host "  Continuar con la instalacion? (s/n)") -ne 's') { Write-Log "Instalacion cancelada por el usuario."; return }

    $dbConfig = Step-ConfigureDatabase -Mode 'Prod'
    $prodConfig = Step-ConfigureProduction

    if (-not (Install-FrontendDependencies)) { Write-Log "Error en dependencias del frontend. Abortando." 'ERROR'; return }

    Step-BuildProduction -DbConfig $dbConfig -ProdConfig $prodConfig
    Invoke-BiometricSetup
    $serviceOk = Step-ConfigureService -DbConfig $dbConfig -ProdConfig $prodConfig
    Step-ConfigureFirewall -ProdConfig $prodConfig
    Save-InstallConfig -DbConfig $dbConfig -ProdConfig $prodConfig

    Write-Host ""
    Write-Host "  ============================================================" -ForegroundColor Green
    Write-Host "  INSTALACION COMPLETADA" -ForegroundColor Green
    Write-Host "  ============================================================" -ForegroundColor Green
    Write-Host "  Servicio:     $ServiceName" -ForegroundColor Cyan
    Write-Host "  Estado:       $(if($serviceOk){'CORRIENDO'}else{'VERIFICAR (posible error)'})" -ForegroundColor $(if($serviceOk){'Green'}else{'Yellow'})
    Write-Host "  Aplicacion:   http://$($prodConfig.ServerIP):$($prodConfig.BackendPort)" -ForegroundColor Cyan
    Write-Host "  Swagger:      http://$($prodConfig.ServerIP):$($prodConfig.BackendPort)/swagger-ui.html" -ForegroundColor Cyan
    Write-Host "  Base de Datos:  $($dbConfig.Host):$($dbConfig.Port)/$($dbConfig.Name)" -ForegroundColor Cyan
    Write-Host "  Config:       $ConfigFile" -ForegroundColor DarkGray
    Write-Host "  Log:          $LogFile" -ForegroundColor DarkGray
    Write-Host ""
}

function Update-App {
    Write-Log "Actualizando aplicacion..." 'STEP'
    if (-not (Test-Path $ConfigFile)) { Write-Log "No se encontro config. Ejecutar instalacion nueva primero." 'ERROR'; return }

    if (Test-Path $NssmExe) { & $NssmExe stop $ServiceName confirm 2>&1 | Out-Null }

    Write-Log "Recompilando frontend..."
    Push-Location $FrontendDir
    & npm run build 2>&1 | ForEach-Object { Write-Log $_ }
    $frontendOk = ($LASTEXITCODE -eq 0)
    Pop-Location
    if (-not $frontendOk) {
        Write-Log "npm run build fallo (codigo $LASTEXITCODE). Se cancela la actualizacion." 'ERROR'
        if (Test-Path $NssmExe) { & $NssmExe start $ServiceName 2>&1 | Out-Null; Write-Log "Se reinicio el servicio con el build anterior." 'WARN' }
        return
    }

    Write-Log "Integrando frontend en el backend..."
    $staticDir = Join-Path $BackendDir "src\main\resources\static"
    if (Test-Path $staticDir) { Remove-Item -Path (Join-Path $staticDir "*") -Recurse -Force -ErrorAction SilentlyContinue }
    else { New-Item -ItemType Directory -Path $staticDir -Force | Out-Null }
    Copy-Item -Path (Join-Path $FrontendDir "dist\*") -Destination $staticDir -Recurse -Force

    Write-Log "Recompilando backend..."
    Push-Location $BackendDir
    & mvn clean package -DskipTests 2>&1 | ForEach-Object {
        if ($_ -match 'BUILD SUCCESS') { Write-Log $_ 'SUCCESS' } elseif ($_ -match 'BUILD FAILURE') { Write-Log $_ 'ERROR' }
    }
    $backendOk = ($LASTEXITCODE -eq 0)
    Pop-Location
    if (-not $backendOk) {
        Write-Log "mvn clean package fallo (codigo $LASTEXITCODE). Se cancela la actualizacion." 'ERROR'
        if (Test-Path $NssmExe) { & $NssmExe start $ServiceName 2>&1 | Out-Null; Write-Log "Se reinicio el servicio con el build anterior." 'WARN' }
        return
    }

    if (Test-Path $NssmExe) {
        & $NssmExe start $ServiceName 2>&1 | Out-Null
        Start-Sleep -Seconds 3
        $status = & $NssmExe status $ServiceName 2>&1
        Write-Log "Servicio reiniciado. Estado: $status" 'SUCCESS'
    }
    Write-Log "Actualizacion completada." 'SUCCESS'
}

function Repair-App {
    Show-Menu "Que deseas reparar?" @(
        "Reconectar base de datos", "Regenerar application-prod.yml",
        "Reinstalar servicio Windows", "Reconfigurar firewall", "Volver al menu"
    )
    $choice = Read-Choice "Seleccionar" -Max 5
    if ($choice -eq 5) { return }

    $config = $null
    if (Test-Path $ConfigFile) { $config = Get-Content $ConfigFile | ConvertFrom-Json }

    switch ($choice) {
        1 {
            $dbConfig = Step-ConfigureDatabase -Mode 'Prod'
            if ($config) { $config.database = $dbConfig; $config | ConvertTo-Json -Depth 5 | Set-Content -Path $ConfigFile -Encoding UTF8 }
            Write-Log "Base de datos reconfigurada. Reinicia el servicio." 'SUCCESS'
        }
        2 {
            if (-not $config) { Write-Log "No hay config previa. Ejecutar instalacion nueva." 'ERROR'; return }
            $dbCfg = @{ Host = $config.database.Host; Port = $config.database.Port; Name = $config.database.Name; User = $config.database.User; Password = $config.database.Password; Url = $config.database.Url }
            $prodCfg = @{ JwtSecret = $config.production.jwtSecret; CorsOrigins = $config.production.corsOrigins; PublicUrl = $config.production.publicUrl; BiometricEncryptionKey = $config.production.biometricEncryptionKey; RateLimitEnabled = $config.production.rateLimitEnabled; RateLimitAuth = '10'; RateLimitScan = '60'; BackendPort = $config.production.backendPort; ServerIP = $config.serverIP }
            Step-BuildProduction -DbConfig $dbCfg -ProdConfig $prodCfg
        }
        3 {
            if (-not $config) { Write-Log "No hay config previa. Ejecutar instalacion nueva." 'ERROR'; return }
            $dbCfg = @{ Host = $config.database.Host; Port = $config.database.Port; Name = $config.database.Name; User = $config.database.User; Password = $config.database.Password; Url = $config.database.Url }
            $prodCfg = @{ JwtSecret = $config.production.jwtSecret; CorsOrigins = $config.production.corsOrigins; PublicUrl = $config.production.publicUrl; BiometricEncryptionKey = $config.production.biometricEncryptionKey; RateLimitEnabled = $config.production.rateLimitEnabled; RateLimitAuth = '10'; RateLimitScan = '60'; BackendPort = $config.production.backendPort; ServerIP = $config.serverIP }
            Step-ConfigureService -DbConfig $dbCfg -ProdConfig $prodCfg
        }
        4 {
            if (-not $config) { Write-Log "No hay config previa. Ejecutar instalacion nueva." 'ERROR'; return }
            Step-ConfigureFirewall -ProdConfig @{ BackendPort = $config.production.backendPort }
        }
    }
}

function Uninstall-App {
    Write-Host ""
    Write-Host "======================================================================" -ForegroundColor Red
    Write-Host "     CONTROL EAT FOOD - DESINSTALADOR" -ForegroundColor Red
    Write-Host "======================================================================" -ForegroundColor Red

    $config = $null
    if (Test-Path $ConfigFile) {
        $config = Get-Content $ConfigFile | ConvertFrom-Json
        Write-Log "Configuracion encontrada: $($config.installDate)"
    } else {
        Write-Log "No se encontro config previa. Desinstalacion generica." 'WARN'
    }

    Write-Host ""
    Write-Host "  Se realizaran las siguientes acciones:" -ForegroundColor Yellow
    Write-Host "    1. Detener y eliminar el servicio '$ServiceName'"
    Write-Host "    2. Eliminar reglas de firewall"
    Write-Host "    3. Opcionalmente: eliminar archivos compilados"
    Write-Host "    4. Opcionalmente: eliminar base de datos (si es local)"
    Write-Host ""
    if ((Read-Host "  Desea continuar? (s/n)") -ne 's') { Write-Log "Desinstalacion cancelada."; return }

    Write-Log "Deteniendo servicio '$ServiceName'..."
    if (Test-Path $NssmExe) {
        & $NssmExe stop $ServiceName confirm 2>&1 | Out-Null
        Start-Sleep -Seconds 2
        & $NssmExe remove $ServiceName confirm 2>&1 | Out-Null
        Write-Log "Servicio detenido y eliminado." 'SUCCESS'
    } else {
        & sc.exe stop $ServiceName 2>&1 | Out-Null
        & sc.exe delete $ServiceName 2>&1 | Out-Null
        Write-Log "Servicio eliminado via sc.exe." 'SUCCESS'
    }

    Write-Log "Eliminando reglas de firewall..."
    $ruleNames = @("ControlEatFood (Port 8080)")
    if ($config) { $ruleNames += "ControlEatFood (Port $($config.production.backendPort))" }
    foreach ($ruleName in ($ruleNames | Select-Object -Unique)) {
        if (Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue) {
            Remove-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
            Write-Log "Regla eliminada: $ruleName" 'SUCCESS'
        }
    }

    Write-Host ""
    if ((Read-Host "  Eliminar archivos compilados (target/, dist/, node_modules/)? (s/n)") -eq 's') {
        $targetDir = Join-Path $BackendDir "target"
        if (Test-Path $targetDir) { Remove-Item -Path $targetDir -Recurse -Force; Write-Log "Eliminado: $targetDir" 'SUCCESS' }
        $distDir = Join-Path $FrontendDir "dist"
        if (Test-Path $distDir) { Remove-Item -Path $distDir -Recurse -Force; Write-Log "Eliminado: $distDir" 'SUCCESS' }
        $nmDir = Join-Path $FrontendDir "node_modules"
        if (Test-Path $nmDir) { Remove-Item -Path $nmDir -Recurse -Force; Write-Log "Eliminado: $nmDir" 'SUCCESS' }
        $prodYmlInResources = Join-Path $BackendDir "src\main\resources\application-prod.yml"
        if (Test-Path $prodYmlInResources) { Remove-Item -Path $prodYmlInResources -Force; Write-Log "Eliminado: application-prod.yml del backend" 'SUCCESS' }
        $staticInResources = Join-Path $BackendDir "src\main\resources\static"
        if (Test-Path $staticInResources) { Remove-Item -Path $staticInResources -Recurse -Force; Write-Log "Eliminado: static/ del backend" 'SUCCESS' }
    }

    if ($config -and $config.database.Type -eq 'local') {
        Write-Host ""
        if ((Read-Host "  Eliminar base de datos local '$($config.database.Name)'? (s/n)") -eq 's') {
            $dockerContainer = $false
            try { $containers = & docker ps -a --format "{{.Names}}" 2>&1; if ($containers -match 'control-mysql') { $dockerContainer = $true } } catch { }
            if ($dockerContainer) {
                if ((Read-Host "  Tambien eliminar contenedor Docker 'control-mysql'? (s/n)") -eq 's') {
                    & docker stop control-mysql 2>&1 | Out-Null
                    & docker rm control-mysql 2>&1 | Out-Null
                    Write-Log "Contenedor Docker 'control-mysql' eliminado." 'SUCCESS'
                }
            } else {
                Write-Log "Eliminar manualmente: DROP DATABASE $($config.database.Name);" 'WARN'
            }
        }
    }

    Write-Host ""
    if ((Read-Host "  Eliminar archivos de configuracion (config/)? (s/n)") -eq 's') {
        if (Test-Path $ConfigDir) { Remove-Item -Path $ConfigDir -Recurse -Force; Write-Log "Directorio config/ eliminado." 'SUCCESS' }
    }

    Write-Host ""
    Write-Host "  ============================================================" -ForegroundColor Green
    Write-Host "  DESINSTALACION COMPLETADA" -ForegroundColor Green
    Write-Host "  ============================================================" -ForegroundColor Green
}

function Show-Diagnostics {
    Write-Log "Ejecutando diagnostico del entorno..." 'STEP'
    Write-Host ""
    Write-Host "  --- Estado del Sistema ---" -ForegroundColor Yellow

    try { Write-Host "    Java:         $(& java -version 2>&1 | Select-Object -First 1)" -ForegroundColor Green }
    catch { Write-Host "    Java:         NO ENCONTRADO" -ForegroundColor Red }
    try { Write-Host "    Node.js:      $(& node --version 2>&1)" -ForegroundColor Green }
    catch { Write-Host "    Node.js:      NO ENCONTRADO" -ForegroundColor Red }
    try { Write-Host "    Maven:        $(& mvn --version 2>&1 | Select-Object -First 1)" -ForegroundColor Green }
    catch { Write-Host "    Maven:        NO ENCONTRADO" -ForegroundColor Red }

    if (Test-Path $NssmExe) { Write-Host "    NSSM:         Instalado" -ForegroundColor Green } else { Write-Host "    NSSM:         NO INSTALADO" -ForegroundColor Red }
    if (Test-Path $NssmExe) {
        $svcStatus = & $NssmExe status $ServiceName 2>&1
        Write-Host "    Servicio:     $svcStatus" -ForegroundColor $(if ($svcStatus -match 'RUNNING') { 'Green' } else { 'Yellow' })
    }
    if (Test-Path $ConfigFile) { Write-Host "    Config:       EXISTE" -ForegroundColor Green } else { Write-Host "    Config:       NO EXISTE" -ForegroundColor Red }

    Write-Host ""
    Write-Host "  --- Puertos en escucha ---" -ForegroundColor Yellow
    foreach ($port in @(8080, 80, 3306, 443)) {
        if (Test-TcpPort -HostName 'localhost' -Port $port) { Write-Host "    Puerto $port :  ABIERTO" -ForegroundColor Green }
        else { Write-Host "    Puerto $port :  CERRADO" -ForegroundColor DarkGray }
    }

    $jarExists = Get-ChildItem (Join-Path $BackendDir "target") -Filter "*.jar" -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch 'sources|javadoc' }
    if ($jarExists) { Write-Host "    JAR Backend:  $($jarExists.Name)" -ForegroundColor Green } else { Write-Host "    JAR Backend:  NO GENERADO" -ForegroundColor Red }
    if (Test-Path (Join-Path $FrontendDir "dist")) { Write-Host "    Frontend:     dist/ EXISTE" -ForegroundColor Green } else { Write-Host "    Frontend:     dist/ NO EXISTE" -ForegroundColor Red }
    Write-Host ""
}

function Show-ProductionMenu {
    while ($true) {
        Clear-Host
        Write-Banner "- MODO PRODUCCION"
        Show-Menu "Seleccionar opcion" @(
            "Instalacion nueva", "Actualizar aplicacion (rebuild + restart)",
            "Reparar configuracion", "Desinstalar", "Diagnosticos del entorno", "Volver al menu principal"
        )
        switch (Read-Choice "Seleccionar opcion" -Max 6) {
            1 { Install-Full }
            2 { Update-App }
            3 { Repair-App }
            4 { if ((Read-Host "  Esta seguro de desinstalar? (s/n)") -eq 's') { Uninstall-App } }
            5 { Show-Diagnostics }
            6 { return }
        }
        Write-Host ""
        Read-Host "  Presione ENTER para continuar"
    }
}

# ═══════════════════════════════════════════════════════════════════
# MENU PRINCIPAL
# ═══════════════════════════════════════════════════════════════════
function Start-DevRelaunch {
    if (-not $script:lastDbConfig) {
        Write-Log "No hay configuración previa. Ejecuta 'Pruebas' primero." 'ERROR'
        Read-Host "  Presione ENTER para continuar"
        return
    }
    
    Stop-ExistingProcesses
    
    $dbConfig = $script:lastDbConfig
    $devJwtSecret = $script:lastDevJwtSecret
    
    Write-Log "Re-lanzando con configuración anterior..." 'STEP'
    Write-Host "  BD: $($dbConfig.Url)" -ForegroundColor Cyan
    Write-Host "  Usuario: $($dbConfig.User)" -ForegroundColor Cyan
    
    $beCmd = "cd `"$BackendDir`"; " +
        "`$env:DB_URL='$($dbConfig.Url)'; `$env:DB_USER='$($dbConfig.User)'; `$env:DB_PASSWORD='$($dbConfig.Password)'; " +
        "`$env:JWT_SECRET='$devJwtSecret'; " +
        "`$env:CORS_ORIGINS='http://localhost:5173,http://localhost:5174,http://localhost:4173'; " +
        "`$env:RATE_LIMIT_ENABLED='true'; `$env:RATE_LIMIT_AUTH='10'; `$env:RATE_LIMIT_SCAN='60'; " +
        "`$env:ZK_NATIVE_PATH='./native'; " +
        "mvn spring-boot:run"
    Start-Process powershell -ArgumentList "-NoExit", "-Command", $beCmd
    Start-Sleep -Seconds 2
    Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd `"$FrontendDir`"; npm run dev"
    
    Write-Log "Backend y frontend re-lanzados en ventanas nuevas." 'SUCCESS'
    Write-Host ""
    Read-Host "  Presione ENTER para volver al menu"
}

function Show-MainMenu {
    while ($true) {
        Clear-Host
        Write-Banner
        
        $options = @("Pruebas (entorno de desarrollo local)")
        if ($script:lastDbConfig) {
            $options += "Re-lanzar con configuración anterior (BD: $($script:lastDbConfig.Host))"
        }
        $options += "Produccion (compilar, servicio de Windows, firewall)"
        $options += "Salir"
        
        Show-Menu "Que deseas hacer?" $options
        
        $maxOption = $options.Count
        $selection = Read-Choice "Seleccionar opcion" -Max $maxOption
        
        if ($selection -eq 1) {
            Start-TestSetup
        }
        elseif ($selection -eq 2 -and $script:lastDbConfig) {
            Start-DevRelaunch
        }
        elseif ($selection -eq 2 -and -not $script:lastDbConfig) {
            if (Test-IsAdmin) {
                Show-ProductionMenu
            } else {
                Write-Log "Se requieren permisos de Administrador para Produccion." 'WARN'
                Start-Process powershell -Verb RunAs -ArgumentList "-NoExit", "-File", "`"$PSCommandPath`""
                return
            }
        }
        elseif ($selection -eq 3 -and $script:lastDbConfig) {
            if (Test-IsAdmin) {
                Show-ProductionMenu
            } else {
                Write-Log "Se requieren permisos de Administrador para Produccion." 'WARN'
                Start-Process powershell -Verb RunAs -ArgumentList "-NoExit", "-File", "`"$PSCommandPath`""
                return
            }
        }
        elseif ($selection -eq 3 -or ($selection -eq 4 -and $script:lastDbConfig)) {
            Write-Host "  Saliendo..." -ForegroundColor Yellow
            break
        }
    }
}

# ═══════════════════════════════════════════════════════════════════
# INICIO
# ═══════════════════════════════════════════════════════════════════
try {
    if ($Mode -eq 'Production') {
        if (-not (Test-IsAdmin)) {
            Write-Log "Este modo requiere privilegios de administrador." 'ERROR'
            Read-Host "  Presione ENTER para salir"
        } else {
            Show-ProductionMenu
            # "Volver al menu principal" desde Produccion cae aqui: como ya
            # estamos elevados, mostramos el menu principal en esta misma
            # ventana en vez de simplemente cerrarla.
            Show-MainMenu
        }
    } else {
        Show-MainMenu
    }
} catch {
    Write-Log "Error inesperado: $_" 'ERROR'
    Write-Log "Stack: $($_.ScriptStackTrace)" 'ERROR'
    Read-Host "  Presione ENTER para salir"
}
