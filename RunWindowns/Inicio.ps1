<#
.SYNOPSIS
    ControlEatFood - Punto de entrada unico (Pruebas / Produccion) para Windows
.DESCRIPTION
    Script unico para todo: reemplaza a setup_env.bat + install.ps1 + uninstall.ps1.
    Un solo menu con dos caminos:
      [1] Pruebas     -> entorno de desarrollo local (no requiere Administrador)
      [2] Produccion  -> build + servicio de Windows (requiere Administrador,
                          se auto-eleva solo)
.NOTES
    Ejecutar directamente:
      powershell -NoProfile -ExecutionPolicy Bypass -File Inicio.ps1
    
    O configurar la politica de ejecucion una sola vez:
      Set-ExecutionPolicy RemoteSigned -Scope CurrentUser
    
    Despues de eso, se puede hacer doble clic o ejecutar directamente.
#>

[CmdletBinding()]
param()

# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
# AUTO-CONFIGURACION INICIAL (reemplaza la funcionalidad de Inicio.bat)
# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
try {
    $currentPolicy = Get-ExecutionPolicy -Scope Process
    if ($currentPolicy -eq 'Restricted' -or $currentPolicy -eq 'Undefined') {
        Set-ExecutionPolicy RemoteSigned -Scope CurrentUser -Force -ErrorAction SilentlyContinue
    }
} catch {
    # Si no se puede cambiar la politica, continuar de todos modos
    # (el usuario puede haber ejecutado el script con -ExecutionPolicy Bypass)
}

Set-StrictMode -Version Latest
# 'Continue' (no 'Stop'): con 'Stop', PowerShell 5.1 convierte cualquier linea
# que un ejecutable nativo escriba en stderr (ej. "java -version", que SIEMPRE
# imprime la version por stderr) en un error terminante en cuanto se usa
# "2>&1" -- rompiendo la deteccion aunque la herramienta si este instalada.
# Los fallos que si deben detener el flujo usan -ErrorAction Stop explicito o
# revisan $LASTEXITCODE, que no depende de esta preferencia.
$ErrorActionPreference = 'Continue'

# $LASTEXITCODE solo existe DESPUES de ejecutar el primer programa externo. Con
# Set-StrictMode, leerlo antes de eso (p. ej. cuando "winget" no esta instalado
# y la llamada ni siquiera llega a ejecutarse -- caso habitual en Windows 10,
# donde winget no viene de fabrica) lanza "La variable '$LASTEXITCODE' no se
# puede recuperar porque no se ha establecido" y tumba el instalador entero.
# Inicializarlo aqui hace que esa lectura sea siempre segura.
$global:LASTEXITCODE = 0

# TLS 1.2 como minimo para TODAS las descargas del script (Maven portable, NSSM).
# Windows 10 en compilaciones antiguas negocia TLS 1.0 por defecto en .NET y los
# CDN de Apache/nssm.cc ya lo rechazan: sin esto, Invoke-WebRequest falla con un
# error de conexion poco descriptivo. Se agrega TLS 1.3 solo si el sistema lo
# soporta (Windows 11 / builds recientes de 10) para no romper en los que no.
try {
    $tlsProtocols = [Net.SecurityProtocolType]::Tls12
    if ([enum]::GetNames([Net.SecurityProtocolType]) -contains 'Tls13') {
        $tlsProtocols = $tlsProtocols -bor [Net.SecurityProtocolType]::Tls13
    }
    [Net.ServicePointManager]::SecurityProtocol = $tlsProtocols
} catch { }

# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
# CONFIGURACION GLOBAL
# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
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

# Secretos persistentes FUERA de RunWindowns\config (que se borra al desinstalar) y
# fuera del repo: sobreviven a una reinstalacion para no invalidar las huellas ya
# cifradas en la BD. Se guardan protegidos con DPAPI (alcance LocalMachine).
$SecretsDir       = Join-Path $env:ProgramData "ControlEatFood"
$BiometricKeyFile = Join-Path $SecretsDir "biometric.key"
$JwtSecretFile    = Join-Path $SecretsDir "jwt.secret"
$DbConfigFile     = Join-Path $SecretsDir "db_config.dat"

@($ConfigDir, $LogsDir, $NssmDir) | ForEach-Object {
    if (-not (Test-Path $_)) { New-Item -ItemType Directory -Path $_ -Force | Out-Null }
}
$LogFile = Join-Path $LogsDir ("inicio_{0}.log" -f (Get-Date -Format "yyyyMMdd_HHmmss"))

# Variables de sesiÃ³n para re-lanzar sin reconfigurar
$script:lastDbConfig = $null
$script:lastDevJwtSecret = $null

# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
# UTILIDADES COMPARTIDAS
# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
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
    # La enye y la tilde se construyen con [char] en lugar de escribirse
    # literalmente: este .ps1 se guarda sin BOM y PowerShell 5.1 lo interpreta
    # como ANSI, con lo que un "Amaguana" con enye literal saldria en pantalla
    # como "AmaguaA+-a". Asi el archivo sigue siendo ASCII puro y el caracter se
    # resuelve en tiempo de ejecucion, que si respeta la pagina de codigos de la
    # consola.
    $n = [char]0xF1  # n con enye
    $o = [char]0xD3  # O mayuscula con tilde
    $line = "=" * 70
    $center = { param([string]$t) (' ' * [Math]::Max(0, [int]((70 - $t.Length) / 2))) + $t }

    Write-Host ""
    Write-Host $line -ForegroundColor Cyan
    Write-Host (& $center "INSTALACI${o}N - CONTROL DE ALMUERZOS") -ForegroundColor White
    Write-Host (& $center "Castillo de Amagua${n}a") -ForegroundColor White
    Write-Host (& $center ("ControlEatFood $Subtitle").Trim()) -ForegroundColor DarkGray
    Write-Host $line -ForegroundColor Cyan
    Write-Host ""
}

# Poda los archivos de stdout/stderr rotados por NSSM en logs/service/.
# Deja solo los 7 mas recientes por base (stdout/stderr); el archivo activo
# 'stdout.log' / 'stderr.log' nunca se toca. 10 MB Ã— 7 â‰ˆ 70 MB max por base.
# Se invoca al instalar, actualizar o Diagnosticos del entorno â€” y siempre
# que se llame Clean-ServiceLogs despues de cualquier rebuild/restart.
function Clean-ServiceLogs {
    param([string]$ServiceLogsDir)
    if (-not $ServiceLogsDir -or -not (Test-Path $ServiceLogsDir)) { return }
    foreach ($base in @('stdout','stderr')) {
        Get-ChildItem $ServiceLogsDir -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -like "$base.log.*" -and $_.Name -ne "$base.log" } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -Skip 7 |
            Remove-Item -Force -ErrorAction SilentlyContinue
    }
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

function Read-Port {
    # Como Read-Default, pero repite el prompt hasta recibir un numero de
    # puerto valido (1-65535). Sin esto, un typo (ej. "erfre") llega intacto
    # hasta el cast "[int]$dbConfig.Port" mas adelante, que lanza una excepcion
    # no controlada y tumba el instalador completo (visto en logs de produccion).
    param([string]$Prompt, [string]$Default)
    do {
        $val = Read-Host "  $Prompt [$Default]"
        if ([string]::IsNullOrWhiteSpace($val)) { return $Default }
        $val = $val.Trim()
        $parsed = 0
        if ([int]::TryParse($val, [ref]$parsed) -and $parsed -ge 1 -and $parsed -le 65535) { return $val }
        Write-Host "    Puerto invalido. Ingrese un numero entre 1 y 65535." -ForegroundColor Red
    } while ($true)
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

function Get-ServerIPs {
    # Deteccion ordenada de IPs IPv4 del host, siguiendo la politica pedida:
    #   1) IP ESTATICA por cable (Ethernet)            <- primera opcion
    #   2) Si no hay lo anterior, IP por DHCP,
    #      ya sea por cable o por Wireless              <- respaldo
    #   3) Cualquier otra IPv4 util como ultimo recurso
    #      (estatica en Wireless, adaptadores virtuales, etc.)
    #
    # Pensado para entornos con maquinas virtuales, donde hay varias redes
    # activas a la vez (Hyper-V, VMware, VirtualBox, WSL, VPN, Docker, TAP...).
    # Si simplemente se agarra la primera IPv4 no loopback, casi siempre se
    # termina recomendando la IP del adaptador virtual (vEthernet / VMware /
    # WSL) en lugar de la de la tarjeta fisica real.
    #
    # Orden de prioridad (menor puntaje = mejor):
    #   0 = IP estatica (PrefixOrigin = Manual) en tarjeta de RED CABLEADA
    #   1 = IP por DHCP en tarjeta de RED CABLEADA
    #   2 = IP por DHCP en tarjeta INALAMBRICA (Wi-Fi)
    #   3 = IP estatica en tarjeta INALAMBRICA
    #   4 = IP estatica en adaptador virtual
    #   5 = IP por DHCP en adaptador virtual
    #   6 = cualquier otra IPv4 no loopback
    # Devuelve un array de strings (IPs unicas) ordenadas de mejor a peor;
    # puede estar vacio si no hay ninguna IPv4 util.
    $virtualHints = @(
        'Hyper-V','VMware','VirtualBox','vEthernet','WSL','TAP-Windows',
        'OpenVPN','WireGuard','Hamachi','ZeroTier','Docker','Tunnel','Loopback'
    )
    $wirelessHints = @('Wi-Fi','WiFi','Wireless','WLAN','802.11')

    # Mapeo InterfaceIndex -> adaptador, para llegar a InterfaceDescription
    # (mas fiable que InterfaceAlias para detectar adaptadores virtuales).
    $adapters = @{}
    try {
        Get-NetAdapter -ErrorAction SilentlyContinue | ForEach-Object {
            $adapters[$_.InterfaceIndex] = $_
        }
    } catch { }

    # OJO: una IP estatica (Manual) queda asignada al adaptador aunque el cable
    # este desconectado -- Get-NetIPAddress la sigue devolviendo igual. Sin
    # cruzar esto con el Status real del adaptador, se termina recomendando
    # una IP fija de una red que ni siquiera esta conectada ahora mismo, en
    # vez de la IP DHCP de la red que si esta activa (ej. Wi-Fi conectado).
    # Si no se encuentra el adaptador (caso raro), se conserva la entrada por
    # seguridad en vez de descartarla a ciegas.
    # Get-NetIPAddress vive en el modulo NetTCPIP y depende de WMI/CIM: en
    # equipos con el repositorio de WMI danado, o en ediciones recortadas de
    # Windows, puede fallar o no devolver nada. Se encierra en try/catch para
    # poder caer al respaldo por .NET en vez de tumbar el instalador.
    $entries = @()
    try {
        $entries = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object {
                $_.IPAddress -ne '127.0.0.1' -and
                $_.IPAddress -notlike '169.254.*' -and
                $_.PrefixOrigin -ne 'WellKnown'
            } |
            Where-Object {
                $a = $adapters[$_.InterfaceIndex]
                (-not $a) -or ($a.Status -eq 'Up')
            })
    } catch {
        $entries = @()
    }

    $ranked = foreach ($e in $entries) {
        $adapter = $adapters[$e.InterfaceIndex]
        $desc    = if ($adapter) { $adapter.InterfaceDescription } else { $e.InterfaceAlias }
        $alias   = $e.InterfaceAlias

        $isVirtual = $false
        foreach ($h in $virtualHints) {
            if ($desc -like "*$h*" -or $alias -like "*$h*") { $isVirtual = $true; break }
        }
        # En Windows se puede clasificar fisico vs virtual con dos senales:
        #   1) Las propiedades booleanas Physical / Virtual de Get-NetAdapter
        #      (NO existen en todas las versiones de Windows/PS 5.1; si faltan,
        #      hay que caer a 2).
        #   2) La decripcion del adaptador: las tarjetas fisicas reales suelen
        #      traer 'PCI', 'USB', 'Ethernet' / el fabricante (Intel, Realtek,
        #      Broadcom, Qualcomm), mientras que los virtuales dicen Hyper-V,
        #      VMware, VirtualBox, TAP, etc. (ya cubiertos por $virtualHints).
        $isPhysical = $false
        if ($adapter) {
            $hasPhysicalProp = $null -ne $adapter.PSObject.Properties['Physical']
            $hasVirtualProp  = $null -ne $adapter.PSObject.Properties['Virtual']
            if ($hasPhysicalProp -and $hasVirtualProp) {
                $isPhysical = [bool]$adapter.Physical -and -not [bool]$adapter.Virtual
            } else {
                $isPhysical = -not $isVirtual
            }
        }
        $isStatic = ($e.PrefixOrigin -eq 'Manual')

        # Cable vs Wireless: PhysicalMediaType/MediaType de Get-NetAdapter es la
        # senal mas fiable ('802.3' = Ethernet cableado, 'Native 802.11' = Wi-Fi).
        # Si el adaptador no trae esa propiedad (o es virtual), se cae a mirar
        # nombre/descripcion contra $wirelessHints; si tampoco matchea nada
        # inalambrico, se asume cableado (Ethernet es el caso mas comun).
        $isWireless = $false
        if ($adapter) {
            $mediaType = [string]$adapter.PhysicalMediaType
            if (-not $mediaType) { $mediaType = [string]$adapter.MediaType }
            if ($mediaType -match '802\.11') { $isWireless = $true }
        }
        if (-not $isWireless) {
            foreach ($h in $wirelessHints) {
                if ($desc -like "*$h*" -or $alias -like "*$h*") { $isWireless = $true; break }
            }
        }
        $isWired = (-not $isVirtual) -and (-not $isWireless)

        $score = 6
        if     ($isStatic -and $isWired)          { $score = 0 }
        elseif (-not $isStatic -and $isWired)      { $score = 1 }
        elseif (-not $isStatic -and -not $isVirtual -and $isWireless) { $score = 2 }
        elseif ($isStatic -and -not $isVirtual -and $isWireless)      { $score = 3 }
        elseif ($isStatic -and $isVirtual)         { $score = 4 }
        elseif (-not $isStatic -and $isVirtual)    { $score = 5 }

        [PSCustomObject]@{ Ip = $e.IPAddress; Score = $score; Alias = $alias; Origin = $e.PrefixOrigin }
    }

    # Deduplica por IP (un mismo IP puede aparecer en varias interfaces) y
    # ordena por puntaje. Devuelve solo el array de IPs.
    $ordered = @($ranked | Sort-Object Score | ForEach-Object { $_.Ip } | Select-Object -Unique)

    # Si los cmdlets de red no dieron nada (Windows 10 con WMI danado, equipos
    # sin el modulo NetTCPIP, etc.), se cae al respaldo por .NET antes de
    # rendirse: es preferible una IP detectada por API nativa que 'localhost'.
    if ($ordered.Count -eq 0) { $ordered = @(Get-ServerIPsFallback) }

    # OJO: PowerShell DESENROLLA el array al devolverlo, asi que con UNA sola IP
    # el llamador recibe un string suelto y con Set-StrictMode "$ips.Count" sobre
    # ese string lanza "No se encuentra la propiedad 'Count' en este objeto" y
    # tumba el instalador -- justo el caso de un equipo con una sola IP
    # (Windows 10 sin VMs ni Hyper-V). POR ESO todo llamador de esta funcion
    # DEBE envolver el resultado en @(...): Get-ServerIP y Read-ServerIP lo hacen.
    return $ordered
}

function Get-ServerIPsFallback {
    # Respaldo sin cmdlets de red: recorre las interfaces con .NET puro, que
    # esta disponible en cualquier Windows con PowerShell 5.1 (10 y 11) y no
    # depende de WMI/CIM. Como ultimo recurso resuelve el nombre del host.
    $result = New-Object System.Collections.Generic.List[string]
    try {
        foreach ($ni in [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
            if ($ni.OperationalStatus -ne [System.Net.NetworkInformation.OperationalStatus]::Up) { continue }
            if ($ni.NetworkInterfaceType -eq [System.Net.NetworkInformation.NetworkInterfaceType]::Loopback) { continue }
            foreach ($ua in $ni.GetIPProperties().UnicastAddresses) {
                if ($ua.Address.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) { continue }
                $ip = "$($ua.Address)"
                if ($ip -eq '127.0.0.1' -or $ip -like '169.254.*') { continue }
                if (-not $result.Contains($ip)) { $result.Add($ip) }
            }
        }
    } catch { }
    if ($result.Count -eq 0) {
        try {
            foreach ($addr in [System.Net.Dns]::GetHostAddresses([System.Net.Dns]::GetHostName())) {
                if ($addr.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) { continue }
                $ip = "$addr"
                if ($ip -eq '127.0.0.1' -or $ip -like '169.254.*') { continue }
                if (-not $result.Contains($ip)) { $result.Add($ip) }
            }
        } catch { }
    }
    # Igual que Get-ServerIPs: el llamador envuelve en @(...) para no depender
    # de si PowerShell desenrolla o no el resultado.
    return $result.ToArray()
}

function Get-ServerIP {
    # Mantiene la API original: devuelve UNA sola IP (la mejor candidata),
    # o 'localhost' si no se detecto ninguna.
    # El @() es defensivo: garantiza un array aunque Get-ServerIPs cambie.
    $ips = @(Get-ServerIPs)
    if ($ips.Count -gt 0 -and $ips[0]) { return $ips[0] }
    return 'localhost'
}

function Read-ServerIP {
    # Pregunta al usuario la IP del servidor y recomienda la detectada por
    # Get-ServerIP (estatica en tarjeta fisica tiene prioridad en entornos con
    # VMs / varias redes). Si se presiona ENTER se acepta la recomendada; si se
    # escribe un valor, se usa ese de forma manual. Muestra todas las IPs
    # detectadas para que el usuario pueda elegir la correcta cuando hay varias
    # redes activas.
    param([string]$Default)

    $recommended = if ($Default) { $Default } else { Get-ServerIP }
    $candidates = @(Get-ServerIPs)
    if ($candidates.Count -eq 0) { $candidates = @($recommended) }
    if ($candidates -notcontains $recommended) { $candidates = @($recommended) + $candidates }

    Write-Host ""
    Write-Host "  --- IP del servidor ---" -ForegroundColor Yellow
    Write-Host "    IPs detectadas en esta maquina:" -ForegroundColor DarkGray
    foreach ($c in $candidates) {
        $marker = if ($c -eq $recommended) { '  (recomendada)' } else { '' }
        Write-Host "      $c$marker" -ForegroundColor DarkGray
    }
    Write-Host ""
    Write-Host "    Si hay varias redes (VMs, VPN, Hyper-V, WSL, Docker)," -ForegroundColor DarkGray
    Write-Host "    elige la IP que los clientes usaran para llegar a este servidor." -ForegroundColor DarkGray
    Write-Host "    Presiona ENTER para usar la recomendada, o escribe la IP manualmente." -ForegroundColor DarkGray
    Write-Host ""
    do {
        $val = Read-Host "  IP del servidor [$recommended]"
        if ([string]::IsNullOrWhiteSpace($val)) { return $recommended }
        $val = $val.Trim()
        if ($val -match '^\d{1,3}(\.\d{1,3}){3}$') { return $val }
        Write-Host "    Formato invalido. Ingresa una IPv4 (ej. 192.168.1.10) o ENTER para la recomendada." -ForegroundColor Red
    } while ($true)
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

# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
# PREREQUISITOS (compartido por Pruebas y Produccion)
# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
function Test-WingetAvailable {
    # winget (App Installer) viene de fabrica en Windows 11, pero en Windows 10
    # es opcional: en muchas instalaciones -- y practicamente nunca en las
    # ediciones LTSC/IoT, que no traen Microsoft Store -- no esta disponible.
    # Sin esta comprobacion, "& winget ..." lanza CommandNotFoundException y,
    # peor aun, deja $LASTEXITCODE sin actualizar.
    return (Test-CommandExists 'winget')
}

function Invoke-Winget {
    # Envoltura segura: nunca lanza y siempre devuelve un codigo de salida
    # numerico (no $null), aunque winget no exista o falle a medio camino.
    param([string[]]$Arguments)
    if (-not (Test-WingetAvailable)) {
        Write-Log "winget no esta disponible en este equipo (habitual en Windows 10 sin App Installer)." 'WARN'
        return 1
    }
    try {
        & winget @Arguments | Out-Null
        if ($null -eq $LASTEXITCODE) { return 1 }
        return $LASTEXITCODE
    } catch {
        Write-Log "winget fallo: $($_.Exception.Message)" 'WARN'
        return 1
    }
}

function Test-Java21 {
    Write-Log "Verificando Java 21..."
    try {
        if (-not (Test-CommandExists 'java')) { throw "java no esta en el PATH" }
        $javaVer = & java -version 2>&1 | Select-String -Pattern '"(\d+)' | ForEach-Object { $_.Matches[0].Groups[1].Value }
        if ($javaVer -eq '21') { Write-Log "Java 21 encontrado." 'SUCCESS'; return $true }
        throw "Java 21 no encontrado"
    } catch {
        Write-Log "Java 21 no esta instalado. Instalando via winget..." 'WARN'
        try {
            $exit = Invoke-Winget @('install', 'EclipseAdoptium.Temurin.21.JDK', '--accept-package-agreements', '--accept-source-agreements')
            Update-SessionPath
            if ($exit -eq 0 -and (Test-CommandExists 'java')) { Write-Log "Java 21 instalado correctamente." 'SUCCESS'; return $true }
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
        $exit = Invoke-Winget @('install', 'OpenJS.NodeJS.LTS', '--accept-package-agreements', '--accept-source-agreements')
        Update-SessionPath
        if ($exit -eq 0 -and (Test-CommandExists 'npm')) { Write-Log "Node.js instalado correctamente." 'SUCCESS'; return $true }
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
    # Si winget no existe (Windows 10 sin App Installer / LTSC), no tiene sentido
    # reintentar 3 veces: se sale de inmediato para que Test-MavenInstalled pase
    # al respaldo real, que es la descarga del ZIP portable de Apache.
    if (-not (Test-WingetAvailable)) {
        Write-Log "winget no disponible: se omite esta via y se usara la descarga portable de Maven." 'WARN'
        return $false
    }
    $maxAttempts = 3
    for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
        Write-Log "Instalando Maven via winget (intento $attempt de $maxAttempts)..."
        $wingetExit = Invoke-Winget @('install', '--id', 'Apache.Maven', '-e', '--accept-package-agreements', '--accept-source-agreements', '--force')
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

# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
# BASE DE DATOS (compartido por Pruebas y Produccion)
# Dev  -> pregunta Local (Docker/MySQL local, auto-detect) o Remoto
# Prod -> siempre remoto: se conecta directo a un servidor, sin Docker
# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

# --- Helpers del contenedor Docker 'control-mysql' (MySQL 8.0, solo modo Pruebas) ---
function Get-ControlMysqlImage {
    # Devuelve el tag de imagen del contenedor 'control-mysql' (exista corriendo o
    # detenido), o $null si no existe / docker no responde.
    try {
        $img = & docker inspect control-mysql --format "{{.Config.Image}}" 2>$null
        if ($LASTEXITCODE -eq 0 -and $img) { return ("$img").Trim() }
    } catch { }
    return $null
}

function Test-MySqlAuth {
    # Prueba, POR TCP (-h127.0.0.1, no por socket), que usuario/password/servidor
    # respondan dentro del contenedor. Durante el primer arranque la imagen inicializa
    # con --skip-networking, asi que esta prueba solo pasa cuando el servidor real ya
    # acepta conexiones (que es justo lo que necesita el backend). Sirve de gate de
    # readiness y para detectar que la password 'admin' del contenedor no coincide.
    param([hashtable]$DbConfig)
    # OJO: el host va con "=" y --protocol=TCP explicito. La forma pegada "-h127.0.0.1"
    # se malinterpreta al pasar por docker exec (el host termina siendo "127" y da
    # "Can't connect to server on '127'"), rompiendo el gate de readiness.
    & docker exec control-mysql mysql --protocol=TCP --host=127.0.0.1 "--user=$($DbConfig.User)" "--password=$($DbConfig.Password)" -e "SELECT 1;" 2>$null | Out-Null
    return ($LASTEXITCODE -eq 0)
}

function Wait-MySqlReady {
    param([hashtable]$DbConfig, [int]$MaxSeconds = 90)
    Write-Log "Esperando a que MySQL 8 acepte conexiones (el primer arranque tarda ~20-60s)..."
    $deadline = (Get-Date).AddSeconds($MaxSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-MySqlAuth -DbConfig $DbConfig) { Write-Log "MySQL 8 listo para aceptar conexiones." 'SUCCESS'; return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}

function New-ControlMysql8Container {
    param([hashtable]$DbConfig)
    # Tag "mysql:8.0" (serie LTS de la 8): el sistema esta homologado a MySQL 8.
    # La imagen ya usa utf8mb4 por defecto, pero se fija la collation
    # utf8mb4_unicode_ci explicitamente para que coincida con la que usan
    # Ensure-Database y el aprovisionamiento de MySQL local.
    Write-Log "Descargando imagen MySQL 8.0..."
    & docker pull mysql:8.0 2>&1 | Out-Null
    Write-Log "Creando contenedor Docker MySQL 8.0..."
    & docker run --name control-mysql -p 3306:3306 `
        -e "MYSQL_ROOT_PASSWORD=$($DbConfig.Password)" `
        -e "MYSQL_DATABASE=$($DbConfig.Name)" `
        -e "MYSQL_USER=$($DbConfig.User)" `
        -e "MYSQL_PASSWORD=$($DbConfig.Password)" `
        -d mysql:8.0 `
        --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Log "Error al crear contenedor Docker." 'ERROR'; return $false }
    Write-Log "Contenedor Docker creado." 'SUCCESS'
    if (-not (Wait-MySqlReady -DbConfig $DbConfig)) {
        Write-Log "El contenedor se creo pero MySQL no acepto la conexion a tiempo. Revisa 'docker logs control-mysql'." 'WARN'
    }
    return $true
}

function Ensure-ControlMysql8 {
    # Deja listo un contenedor 'control-mysql' basado en mysql:8.0 con la BD/usuario/
    # password dados. Maneja tres casos:
    #   - No existe             -> lo crea.
    #   - Existe con otra imagen (p.ej. la vieja mysql:5.6) -> avisa y ofrece recrearlo
    #     (borra los datos del contenedor viejo; es un entorno de pruebas local).
    #   - Existe en mysql:8     -> lo inicia; si la password de 'admin' no valida, ofrece
    #     recrearlo desde cero con la password ingresada.
    param([hashtable]$DbConfig)

    $img = Get-ControlMysqlImage
    if (-not $img) { return (New-ControlMysql8Container -DbConfig $DbConfig) }

    if ($img -notmatch '^mysql:8') {
        Write-Log "El contenedor 'control-mysql' usa la imagen '$img', no MySQL 8." 'WARN'
        Write-Host "    El sistema esta homologado a MySQL 8: hay que recrearlo. Esto BORRA los" -ForegroundColor Yellow
        Write-Host "    datos de ese contenedor viejo (es un entorno de pruebas local)." -ForegroundColor Yellow
        if ((Read-Host "  Recrear 'control-mysql' como MySQL 8.0? (s/n, ENTER = s)") -eq 'n') {
            Write-Log "Se conserva el contenedor '$img'; la app se conectara a esa version." 'WARN'
            & docker start control-mysql 2>&1 | Out-Null
            return $true
        }
        & docker rm -f control-mysql 2>&1 | Out-Null
        return (New-ControlMysql8Container -DbConfig $DbConfig)
    }

    Write-Log "Contenedor 'control-mysql' (MySQL 8) ya existe. Iniciandolo..."
    & docker start control-mysql 2>&1 | Out-Null
    if (Wait-MySqlReady -DbConfig $DbConfig) { return $true }

    Write-Log "El contenedor MySQL 8 existe pero la password del usuario '$($DbConfig.User)' no coincide con la ingresada." 'WARN'
    Write-Host "    (La password del usuario se fija al crear el contenedor y no se puede cambiar" -ForegroundColor Yellow
    Write-Host "     con variables de entorno al reiniciarlo.)" -ForegroundColor Yellow
    if ((Read-Host "  Recrear el contenedor desde cero con la password ingresada? (s/n, ENTER = s)") -eq 'n') {
        Write-Log "Se conserva el contenedor; usa la password original del usuario '$($DbConfig.User)'." 'WARN'
        return $true
    }
    & docker rm -f control-mysql 2>&1 | Out-Null
    return (New-ControlMysql8Container -DbConfig $DbConfig)
}

# ---------------------------------------------------------------------------
# BASE DE DATOS
# ---------------------------------------------------------------------------
# El instalador solo se asegura de que la BASE exista. La estructura (tablas) y
# los datos iniciales son responsabilidad EXCLUSIVA de Flyway, que corre al
# arrancar el backend con las migraciones de
# controlEatFoodWeb\backend\src\main\resources\db\migration (V1 esquema, V2 seed).
#
# Antes habia aqui unos scripts db\01_esquema.sql y db\02_datos.sql que creaban
# el esquema por fuera. Eran una copia manual de V1/V2 y se desincronizaron:
# poblaban la base sin dejar flyway_schema_history, y el backend no arrancaba
# ("Found non-empty schema but no schema history table"). Una sola fuente de
# verdad evita esa clase de fallo, y ademas los scripts no sabian ACTUALIZAR un
# esquema ya existente (CREATE TABLE IF NOT EXISTS no altera columnas), cosa que
# Flyway si hace al agregar V3, V4, ... sobre instalaciones con datos.
#
# Regla: todo cambio de esquema es una migracion V*.sql nueva; jamas se edita
# una migracion ya aplicada.

$script:MySqlExePath = $null
function Get-MySqlExe {
    # Devuelve la ruta de mysql.exe, o $null si no aparece por ningun lado.
    # El instalador oficial de MySQL para Windows NO agrega su 'bin' al PATH, asi
    # que buscar solo con Get-Command daba "no encontrado" aunque el servidor
    # estuviera instalado y corriendo. Se busca en PATH y, si no, en las rutas
    # habituales (MySQL Server, MariaDB, XAMPP, Laragon, WAMP).
    if ($script:MySqlExePath) { return $script:MySqlExePath }

    $cmd = Get-Command 'mysql' -ErrorAction SilentlyContinue
    if ($cmd) { $script:MySqlExePath = $cmd.Source; return $script:MySqlExePath }

    $roots = @(
        "$env:ProgramFiles\MySQL", "${env:ProgramFiles(x86)}\MySQL",
        "$env:ProgramFiles\MariaDB", "${env:ProgramFiles(x86)}\MariaDB"
    ) | Where-Object { $_ -and (Test-Path $_) }
    foreach ($root in $roots) {
        # -Depth 2 alcanza "MySQL Server 8.0\bin\mysql.exe" sin recorrer todo el arbol.
        $found = Get-ChildItem -Path $root -Filter 'mysql.exe' -Recurse -Depth 2 -ErrorAction SilentlyContinue |
                 Sort-Object FullName -Descending | Select-Object -First 1
        if ($found) { $script:MySqlExePath = $found.FullName; return $script:MySqlExePath }
    }
    foreach ($p in @("$env:SystemDrive\xampp\mysql\bin\mysql.exe", "$env:SystemDrive\laragon\bin\mysql\bin\mysql.exe", "$env:SystemDrive\wamp64\bin\mysql\bin\mysql.exe")) {
        if (Test-Path $p) { $script:MySqlExePath = $p; return $script:MySqlExePath }
    }
    return $null
}

function Test-MySqlClientExists { return $null -ne (Get-MySqlExe) }

function Test-UseDockerClient {
    # Decide si las sentencias SQL se ejecutan dentro del contenedor 'control-mysql'
    # o con el mysql.exe del PATH. Si el usuario eligio motor explicitamente
    # (Engine), esa eleccion manda; si no (config antigua sin el campo), se cae a
    # la deteccion de siempre: hay docker y existe el contenedor.
    param([hashtable]$DbConfig)
    if ($DbConfig.Type -ne 'local') { return $false }
    if ($DbConfig.Engine -eq 'mysql')  { return $false }
    if ($DbConfig.Engine -eq 'docker') { return ((Test-CommandExists 'docker') -and [bool](Get-ControlMysqlImage)) }
    return ((Test-CommandExists 'docker') -and [bool](Get-ControlMysqlImage))
}

function Invoke-MySqlClient {
    param(
        [hashtable]$DbConfig,
        [string]$Query,      # sentencia SQL inline (-e)
        [string]$SqlFile,    # o archivo .sql completo por stdin
        [switch]$SelectDb,   # agregar el nombre de la BD como argumento
        [switch]$Silent      # -N -s: sin cabeceras, salida cruda (para SELECT)
    )
    # Ejecutor: el contenedor Docker 'control-mysql' (modo local) o mysql.exe del PATH.
    $useDocker = Test-UseDockerClient -DbConfig $DbConfig
    $mysqlArgs = @(
        '--protocol=TCP',
        "--host=$(if ($useDocker) { '127.0.0.1' } else { $DbConfig.Host })",
        "--port=$($DbConfig.Port)",
        "--user=$($DbConfig.User)",
        "--password=$($DbConfig.Password)",
        '--default-character-set=utf8mb4'
    )
    if ($Silent)   { $mysqlArgs += @('-N', '-s') }
    if ($Query)    { $mysqlArgs += @('-e', $Query) }
    if ($SelectDb) { $mysqlArgs += $DbConfig.Name }

    if ($SqlFile) {
        # El archivo va por stdin; $OutputEncoding controla la codificacion del
        # pipe hacia el ejecutable nativo (en PS 5.1 el default es ASCII y
        # destrozaria los acentos de los scripts).
        $sql = Get-Content -Path $SqlFile -Raw -Encoding UTF8
        $prevEnc = $OutputEncoding
        try {
            $OutputEncoding = New-Object System.Text.UTF8Encoding($false)
            if ($useDocker) { $sql | & docker exec -i control-mysql mysql @mysqlArgs 2>&1 }
            else            { $sql | & (Get-MySqlExe) @mysqlArgs 2>&1 }
        } finally { $OutputEncoding = $prevEnc }
    }
    elseif ($useDocker) { & docker exec control-mysql mysql @mysqlArgs 2>&1 }
    else                { & (Get-MySqlExe) @mysqlArgs 2>&1 }
}

function Ensure-Database {
    param([hashtable]$DbConfig)

    # Solo garantiza que exista la BASE (CREATE DATABASE). Las tablas y los datos
    # iniciales los crea Flyway al arrancar el backend. Es idempotente: sobre una
    # instalacion que ya tiene datos no toca absolutamente nada.
    if (-not $DbConfig.Password) {
        Write-Log "Sin credenciales de BD (configuracion omitida): no se verifica la existencia de la base." 'WARN'
        return
    }

    # En instalacion local la URL lleva createDatabaseIfNotExist=true, asi que el
    # propio driver JDBC crea la base al conectar y el cliente mysql es opcional.
    # En BD remota la URL no lo lleva: ahi la base tiene que existir si o si.
    $driverCreaLaBase = $DbConfig.Url -match 'createDatabaseIfNotExist=true'

    $useDocker = Test-UseDockerClient -DbConfig $DbConfig
    if (-not $useDocker -and -not (Test-MySqlClientExists)) {
        if ($driverCreaLaBase) {
            Write-Log "Sin cliente 'mysql', pero la URL lleva createDatabaseIfNotExist: el backend creara la base al conectar." 'INFO'
        } else {
            Write-Log "No se encontro el cliente 'mysql' (ni en PATH ni instalado, ni contenedor Docker)." 'WARN'
            Write-Log "La base '$($DbConfig.Name)' debe existir YA en $($DbConfig.Host) o el backend no podra arrancar." 'WARN'
        }
        return
    }

    Write-Log "Verificando que exista la base '$($DbConfig.Name)'..." 'STEP'
    Invoke-MySqlClient -DbConfig $DbConfig -Query "CREATE DATABASE IF NOT EXISTS ``$($DbConfig.Name)`` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Log "No se pudo crear ni verificar la base con esas credenciales." 'WARN'
        if (-not $driverCreaLaBase) {
            Write-Log "Comprueba que '$($DbConfig.Name)' exista en $($DbConfig.Host) y que el usuario '$($DbConfig.User)' tenga permisos sobre ella." 'WARN'
        }
        return
    }
    Write-Log "Base '$($DbConfig.Name)' lista; Flyway creara las tablas al arrancar el backend." 'SUCCESS'
}

function Step-ConfigureDatabase {
    param([ValidateSet('Dev', 'Prod')][string]$Mode)

    Write-Log "Configurar conexion a base de datos ($Mode)..." 'STEP'

    $dbConfig = @{
        Type = ''; Engine = ''; Host = 'localhost'; Port = '3306'
        Name = 'control_almuerzos'; User = 'admin'; Password = ''; Url = ''; SslMode = 'PREFERRED'
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

        # Se muestra tambien la imagen del contenedor 'control-mysql': un contenedor
        # viejo (p. ej. la antigua mysql:5.6) tambien abre el 3306, y saberlo evita
        # elegir Docker creyendo que es MySQL 8 y terminar con "Access denied for
        # user 'admin'" (si se elige, Ensure-ControlMysql8 ofrece recrearlo).
        $dockerOk    = Test-CommandExists 'docker'
        $controlImage = if ($dockerOk) { Get-ControlMysqlImage } else { $null }
        $mysqlExe    = Get-MySqlExe
        $mysqlOk     = $null -ne $mysqlExe
        $portOpen    = Test-TcpPort -HostName 'localhost' -Port 3306

        # La deteccion ya no decide por el usuario: solo se muestra como pista y
        # es el usuario quien elige contra que motor local se va a trabajar.
        Write-Host ""
        Write-Host "  --- Deteccion del entorno local ---" -ForegroundColor Cyan
        if ($dockerOk) {
            if ($controlImage) { Write-Host "    Docker: disponible (contenedor 'control-mysql' con imagen '$controlImage')" }
            else               { Write-Host "    Docker: disponible (sin contenedor 'control-mysql')" }
        } else {
            Write-Host "    Docker: no instalado"
        }
        Write-Host "    Cliente mysql.exe: $(if ($mysqlOk) { $mysqlExe } else { 'no encontrado' })"
        Write-Host "    Puerto 3306: $(if ($portOpen) { 'en uso (hay un MySQL corriendo)' } else { 'libre' })"
        Write-Host ""

        Show-Menu "Donde esta el MySQL local que vas a usar?" @(
            "Contenedor Docker (control-mysql, MySQL 8.0)",
            "MySQL instalado en esta maquina (servicio local)",
            "Omitir (lo configuro despues)"
        )
        $mysqlChoice = Read-Choice "Seleccionar opcion" -Max 3

        # Credenciales: se piden siempre (salvo al omitir), sea Docker o MySQL local.
        if ($mysqlChoice -ne 3) {
            $dbConfig.Name     = Read-Default "Nombre de base de datos" $dbConfig.Name
            $dbConfig.User     = Read-Default "Usuario de MySQL" $dbConfig.User
            $dbConfig.Password = Read-RequiredInput "Contrasena para el usuario '$($dbConfig.User)' de MySQL (obligatoria)"
        }

        # 'Engine' deja registrado contra que motor eligio trabajar el usuario, para
        # que el cliente mysql (Invoke-MySqlClient) no lo vuelva a adivinar: con un
        # contenedor 'control-mysql' presente pero habiendo elegido el MySQL de la
        # maquina, adivinar mandaba los scripts al contenedor equivocado.
        $dbConfig.Engine = @{ 1 = 'docker'; 2 = 'mysql'; 3 = '' }[$mysqlChoice]

        switch ($mysqlChoice) {
            1 {
                if (-not $dockerOk) {
                    Write-Log "Docker no esta instalado." 'ERROR'
                    Start-Process "https://www.docker.com/products/docker-desktop/"
                    Read-Host "  Presione ENTER cuando Docker este instalado"
                }
                # Ensure-ControlMysql8 cubre los tres casos: no existe (lo crea),
                # existe con otra imagen (ofrece recrearlo) y existe en mysql:8
                # (lo inicia y valida la password).
                Ensure-ControlMysql8 -DbConfig $dbConfig | Out-Null
            }
            2 {
                if (-not $mysqlOk) {
                    Write-Log "No se encontro mysql.exe (ni en PATH ni en las rutas de instalacion habituales)." 'ERROR'
                    Write-Log "Instala MySQL 8, o agrega su carpeta 'bin' al PATH, y vuelve a intentarlo." 'WARN'
                    Read-Host "  Presione ENTER cuando MySQL este configurado"
                    $script:MySqlExePath = $null   # forzar una nueva busqueda tras el ENTER
                    $mysqlExe = Get-MySqlExe
                    $mysqlOk  = $null -ne $mysqlExe
                } else {
                    Write-Log "Cliente mysql: $mysqlExe" 'SUCCESS'
                }
                if ($portOpen) {
                    Write-Log "Se detecto un servicio en el puerto 3306 (MySQL ya esta corriendo)." 'SUCCESS'
                }
                $rootPass = Read-Host "  Contrasena de root en MySQL local (ENTER para omitir el aprovisionamiento)"
                if ($rootPass -and $mysqlOk) {
                    # Sintaxis de MySQL 8: "GRANT ... IDENTIFIED BY" (idioma de 5.6)
                    # fue eliminado; el usuario se crea/actualiza con CREATE USER IF
                    # NOT EXISTS + ALTER USER (fija la password aunque ya existiera)
                    # y el GRANT va aparte.
                    & $mysqlExe -u root -p"$rootPass" -e "CREATE DATABASE IF NOT EXISTS $($dbConfig.Name) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER IF NOT EXISTS '$($dbConfig.User)'@'localhost' IDENTIFIED BY '$($dbConfig.Password)'; ALTER USER '$($dbConfig.User)'@'localhost' IDENTIFIED BY '$($dbConfig.Password)'; GRANT ALL PRIVILEGES ON $($dbConfig.Name).* TO '$($dbConfig.User)'@'localhost'; FLUSH PRIVILEGES;" 2>&1 | Out-Null
                    if ($LASTEXITCODE -eq 0) { Write-Log "Base de datos y usuario configurados localmente." 'SUCCESS' }
                    else { Write-Log "Error al configurar MySQL local." 'ERROR' }
                }
            }
            3 { Write-Log "Configuracion de DB omitida. Configurala manualmente." 'WARN' }
        }
        # allowPublicKeyRetrieval=true: necesario con la autenticacion por defecto
        # de MySQL 8 (caching_sha2_password) cuando la conexion no usa SSL.
        $dbConfig.Url = "jdbc:mysql://localhost:$($dbConfig.Port)/$($dbConfig.Name)?createDatabaseIfNotExist=true&serverTimezone=America/Guayaquil&allowPublicKeyRetrieval=true"
    }
    else {
        # ============ REMOTO (Pruebas -> remoto, o siempre en Produccion) ============
        $dbConfig.Type = 'remote'
        Write-Log "Configuracion de base de datos remota..."

        # Host por defecto 'localhost' (ENTER lo acepta): el servidor MySQL puede
        # estar en esta misma maquina; tambien se puede escribir una IP o un
        # hostname de red. Nombre de BD, usuario y password no sugieren nada:
        # se ingresan obligatoriamente.
        $dbConfig.Name = ''
        $dbConfig.User = ''
        $dbConfig.Password = ''

        # En Produccion se precargan host/puerto/nombre/usuario/password de la
        # ultima conexion que funciono (persistida fuera de config/, sobrevive
        # a un Desinstalar). Asi una Reinstalacion reconecta con ENTER, ENTER,
        # ENTER... en vez de forzar a retipear todo de memoria y arriesgarse a
        # apuntar a una BD distinta (o vacia) donde las huellas ya cifradas no
        # sirven de nada.
        if ($Mode -eq 'Prod') {
            $savedDb = Read-PersistedDbConfig
            if ($savedDb) {
                $dbConfig.Host = $savedDb.Host
                $dbConfig.Port = $savedDb.Port
                $dbConfig.Name = $savedDb.Name
                $dbConfig.User = $savedDb.User
                $dbConfig.Password = $savedDb.Password
                if ($savedDb.SslMode) { $dbConfig.SslMode = $savedDb.SslMode }
                Write-Log "Datos de conexion a BD recuperados de la ultima instalacion (ProgramData)." 'SUCCESS'
            }
        }

        $connSuccess = $false
        do {
            $dbConfig.Host = if ($dbConfig.Host) { Read-Default "IP/Host del servidor MySQL" $dbConfig.Host } else { Read-RequiredInput "IP/Host del servidor MySQL" }
            $dbConfig.Port = Read-Port "Puerto" $dbConfig.Port
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

        # Cifrado de la conexion a MySQL: se pregunta SIEMPRE que la conexion sea
        # remota (tanto en Pruebas como en Produccion). Muchos servidores MySQL
        # no tienen TLS configurado, asi que el usuario debe poder indicar 'n'.
        Write-Host ""
        Write-Host "  --- Cifrado de la conexion a MySQL (SSL/TLS) ---" -ForegroundColor Yellow
        $sslDefault = if ($dbConfig.SslMode -eq 'DISABLED') { 'n' } else { 's' }
        $useSsl = Read-Host "  El servidor MySQL soporta conexion SSL/TLS? (s = si / n = no, ENTER = $sslDefault)"
        if ([string]::IsNullOrWhiteSpace($useSsl)) { $useSsl = $sslDefault }
        if ($useSsl -ne 'n') {
            # REQUIRED cifra la conexion pero no valida el certificado contra una CA
            # (apropiado para un MySQL con certificado autofirmado/por defecto, sin CA propia).
            $dbConfig.SslMode = 'REQUIRED'
            Write-Log "Conexion a MySQL: sslMode=REQUIRED (cifrada, sin verificar CA)." 'SUCCESS'
        } else {
            $dbConfig.SslMode = 'DISABLED'
            Write-Log "Conexion a MySQL SIN cifrar (sslMode=DISABLED)." 'WARN'
        }

        $dbConfig.Url = "jdbc:mysql://$($dbConfig.Host):$($dbConfig.Port)/$($dbConfig.Name)?sslMode=$($dbConfig.SslMode)&serverTimezone=America/Guayaquil&allowPublicKeyRetrieval=true"
    }

    Write-Log "DB_URL = $($dbConfig.Url)" 'SUCCESS'

    if ($Mode -eq 'Prod' -and $dbConfig.Password) {
        Save-PersistedDbConfig -DbConfig $dbConfig
        Write-Log "Datos de conexion a BD guardados en ProgramData para futuras reinstalaciones." 'SUCCESS'
    }

    # Asegurar solo que la BASE exista; las tablas y el seed los crea Flyway al
    # arrancar el backend. Nunca es fatal: si algo falta, el log del backend lo
    # dira con precision.
    try { Ensure-Database -DbConfig $dbConfig }
    catch { Write-Log "Ensure-Database fallo: $_" 'WARN' }

    return $dbConfig
}

# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
# FRONTEND / SDK BIOMETRICO (compartido)
# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
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

# Detecta si el SDK del lector ZK9500 ya esta instalado A NIVEL DE SISTEMA.
# setup.exe copia libzkfp.dll (y libzkfpcsharp.dll) a System32/SysWOW64; esa
# instalacion es permanente y sobrevive a redespliegues del proyecto y a desinstalar
# la app. Es la MISMA DLL que el backend carga con Native.load("libzkfp") desde
# System32 (ver ZkBiometricMatcher, "Prioridad 1"). Detectarla es la fuente de verdad:
# evita reinstalar el SDK en cada puesta en produccion aunque falte el marcador local.
function Test-BiometricSdkInstalled {
    foreach ($dir in @("$env:WINDIR\System32", "$env:WINDIR\SysWOW64")) {
        if (Test-Path (Join-Path $dir "libzkfp.dll")) { return $true }
    }
    return $false
}

function Invoke-BiometricSetup {
    Write-Log "Verificando setup.exe (SDK biometrico)..." 'STEP'
    if (Test-Path $LockFile) {
        Write-Log "setup.exe ya fue ejecutado previamente en esta maquina." 'SUCCESS'
        return
    }
    # El SDK se instala UNA vez y queda de por vida en el sistema (libzkfp.dll en System32).
    # Si ya esta presente NO reinstalamos: solo re-creamos el marcador para que los arranques
    # futuros tomen la ruta rapida. Cubre el caso de redesplegar el proyecto en un servidor
    # donde el SDK ya se instalo antes (marcador perdido pero DLL presente en el sistema).
    if (Test-BiometricSdkInstalled) {
        Write-Log "SDK del lector ZK9500 ya instalado en el sistema (libzkfp.dll en System32). Se omite setup.exe." 'SUCCESS'
        "OK (SDK ya presente en System32; setup.exe omitido)" | Set-Content -Path $LockFile -Encoding ASCII
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

# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
# MODO PRUEBAS (equivalente a setup_env.bat, mejorado)
# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
function Stop-ExistingProcesses {
    Write-Log "Cerrando procesos anteriores del backend/frontend..." 'STEP'
    
    # Cerrar ventanas del shell asociadas al proyecto. Shell.Application no
    # esta garantizado: falla si no hay sesion de Explorer activa y, corriendo
    # elevado, devuelve una coleccion vacia. Va en try/catch porque no es
    # esencial -- lo importante es matar los procesos java/node de mas abajo.
    try {
        $shell = New-Object -ComObject Shell.Application -ErrorAction Stop
        foreach ($window in @($shell.Windows())) {
            try {
                if ("$($window.LocationName)" -match 'ControlEatFood') {
                    $window.Quit()
                    Start-Sleep -Milliseconds 500
                }
            } catch { }
        }
    } catch { }
    
    # Matar procesos de Java (backend) que estÃ©n usando el puerto 3000
    $javaProcesses = Get-Process -Name java -ErrorAction SilentlyContinue
    if ($javaProcesses) {
        foreach ($proc in $javaProcesses) {
            try {
                $connections = Get-NetTCPConnection -OwningProcess $proc.Id -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -eq 3000 }
                if ($connections) {
                    # Cierre ELEGANTE primero: 'taskkill' SIN /F envia WM_CLOSE/CTRL_CLOSE_EVENT,
                    # lo que dispara el shutdown hook de la JVM y el @PreDestroy del backend
                    # (ZKFPM_Terminate + CloseDevice) que LIBERA el lector ZK9500. Un kill forzado
                    # deja el ZK9500 bloqueado y el siguiente arranque no lo puede abrir (OpenDevice=null).
                    cmd /c "taskkill /PID $($proc.Id) /T >nul 2>&1" | Out-Null
                    $exited = $false
                    for ($i = 0; $i -lt 24; $i++) {
                        Start-Sleep -Milliseconds 500
                        if (-not (Get-Process -Id $proc.Id -ErrorAction SilentlyContinue)) { $exited = $true; break }
                    }
                    if ($exited) {
                        Write-Log "Backend cerrado limpiamente (PID: $($proc.Id)) - lector ZK9500 liberado." 'SUCCESS'
                    } else {
                        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
                        Write-Log "Backend no respondio al cierre limpio; forzado (PID: $($proc.Id)). El ZK9500 podria quedar bloqueado." 'WARN'
                    }
                }
            } catch { }
        }
    }
    
    # Matar procesos de Node (frontend) que estÃ©n usando el puerto 4173 (vite.config.js)
    $nodeProcesses = Get-Process -Name node -ErrorAction SilentlyContinue
    if ($nodeProcesses) {
        foreach ($proc in $nodeProcesses) {
            try {
                $connections = Get-NetTCPConnection -OwningProcess $proc.Id -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in @(4173, 5173) }
                if ($connections) {
                    $proc | Stop-Process -Force -ErrorAction SilentlyContinue
                    Write-Log "Proceso frontend cerrado (PID: $($proc.Id))." 'SUCCESS'
                }
            } catch { }
        }
    }
    
    Start-Sleep -Seconds 1
}

# Regla de firewall para el backend en modo Pruebas. Sin ella, Windows descarta
# EN SILENCIO las conexiones entrantes de la app movil al puerto 3000 (sobre todo
# con la red Wi-Fi marcada como "Publica") y el telefono muestra "Tiempo de espera
# agotado" aunque el backend este corriendo: desde el propio PC (localhost) todo
# funciona, por eso el fallo solo se ve desde el telefono. Produccion ya crea la
# regla en Step-ConfigureFirewall; Pruebas no corre elevado, asi que si hay que
# crearla se pide elevacion puntual (UAC) solo para ese comando.
function Ensure-DevFirewallRule {
    param([int]$Port = 3000)
    $ruleName = "ControlEatFood (Port $Port)"
    if (Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue) {
        Write-Log "Regla de firewall ya existe: $ruleName" 'SUCCESS'
        return
    }
    $isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    if ($isAdmin) {
        try {
            New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow -Profile Any -ErrorAction Stop | Out-Null
            Write-Log "Regla de firewall creada: $ruleName" 'SUCCESS'
        } catch {
            Write-Log "No se pudo crear la regla de firewall: $($_.Exception.Message)" 'ERROR'
        }
        return
    }
    Write-Log "Falta la regla de firewall '$ruleName': el telefono NO podra conectarse al backend (Tiempo de espera agotado)." 'WARN'
    $answer = Read-Host "  Crear la regla ahora? Se pedira permiso de Administrador (UAC) (s/n, ENTER = s)"
    if ($answer -eq 'n') {
        Write-Log "Regla omitida. La app movil seguira sin poder conectarse hasta abrir el puerto $Port." 'WARN'
        return
    }
    try {
        $cmd = "New-NetFirewallRule -DisplayName '$ruleName' -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow -Profile Any"
        Start-Process powershell -Verb RunAs -Wait -ArgumentList '-NoProfile', '-WindowStyle', 'Hidden', '-Command', $cmd
    } catch {
        # El usuario cancelo el UAC o la elevacion fallo; se informa abajo.
    }
    if (Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue) {
        Write-Log "Regla de firewall creada: $ruleName" 'SUCCESS'
    } else {
        Write-Log "No se creo la regla. Ejecuta en PowerShell como Administrador: New-NetFirewallRule -DisplayName '$ruleName' -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow -Profile Any" 'ERROR'
    }
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
    Ensure-DevFirewallRule -Port 3000

    # Guardar configuraciÃ³n en variable de sesiÃ³n para re-lanzar despuÃ©s
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
        "Volver al menu principal"
    )
    $action = Read-Choice "Seleccionar" -Max 2

    switch ($action) {
        1 {
            Stop-ExistingProcesses
            $beEnv = "cd `"$BackendDir`"; " +
                "`$env:DB_URL='$($dbConfig.Url)'; `$env:DB_USER='$($dbConfig.User)'; `$env:DB_PASSWORD='$($dbConfig.Password)'; " +
                "`$env:JWT_SECRET='$devJwtSecret'; " +
                "`$env:CORS_ORIGINS='http://localhost:5173,http://localhost:5174,http://localhost:4173'; " +
                "`$env:RATE_LIMIT_ENABLED='true'; `$env:RATE_LIMIT_AUTH='10'; `$env:RATE_LIMIT_SCAN='60'; " +
                "`$env:ZK_NATIVE_PATH='$ZkNativePath'; "
            # Primer lanzamiento con 'clean': borra target/classes y lo repuebla desde
            # src, eliminando cualquier migracion Flyway obsoleta que hubiera quedado
            # compilada de un build anterior (p. ej. V3..V11 tras consolidarlas en V1).
            # Sin esto, Flyway corre migraciones viejas que ya no aplican y falla.
            # El relanzamiento usa la version sin 'clean' para reiniciar rapido.
            $beCmdClean = $beEnv + "mvn clean spring-boot:run"
            $beCmd      = $beEnv + "mvn spring-boot:run"
            Start-Process powershell -ArgumentList "-NoExit", "-Command", $beCmdClean
            Start-Sleep -Seconds 2
            Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd `"$FrontendDir`"; npm run dev"
            Write-Log "Backend y frontend lanzados en ventanas nuevas." 'SUCCESS'
            Write-Host ""

            do {
                Show-Menu "Backend y frontend en ejecucion. Que deseas hacer?" @(
                    "Relanzar backend y frontend (con datos actuales)",
                    "Volver al menu principal"
                )
                $postAction = Read-Choice "Seleccionar" -Max 2

                switch ($postAction) {
                    1 {
                        Stop-ExistingProcesses
                        Start-Process powershell -ArgumentList "-NoExit", "-Command", $beCmd
                        Start-Sleep -Seconds 2
                        Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd `"$FrontendDir`"; npm run dev"
                        Write-Log "Backend y frontend relanzados." 'SUCCESS'
                        Write-Host ""
                    }
                    2 {
                        return
                    }
                }
            } while ($true)
        }
        2 {
            return
        }
    }
}

# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
# MODO PRODUCCION (equivalente a install.ps1 + uninstall.ps1)
# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
function Save-PersistedSecret {
    param([string]$Path, [string]$Value)
    $dir = Split-Path -Parent $Path
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    $toWrite = $null
    try {
        Add-Type -AssemblyName System.Security -ErrorAction Stop
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
        $protected = [System.Security.Cryptography.ProtectedData]::Protect(
            $bytes, $null, [System.Security.Cryptography.DataProtectionScope]::LocalMachine)
        $toWrite = "DPAPI:" + [Convert]::ToBase64String($protected)
    } catch {
        Write-Log "DPAPI no disponible; el secreto se guarda en claro en $Path." 'WARN'
        $toWrite = "PLAIN:" + $Value
    }
    [System.IO.File]::WriteAllText($Path, $toWrite)
}

function Read-PersistedSecret {
    param([string]$Path)
    if (-not (Test-Path $Path)) { return $null }
    $raw = (Get-Content -Path $Path -Raw).Trim()
    if (-not $raw) { return $null }
    if ($raw.StartsWith("PLAIN:")) { return $raw.Substring(6) }
    if ($raw.StartsWith("DPAPI:")) {
        try {
            Add-Type -AssemblyName System.Security -ErrorAction Stop
            $protected = [Convert]::FromBase64String($raw.Substring(6))
            $bytes = [System.Security.Cryptography.ProtectedData]::Unprotect(
                $protected, $null, [System.Security.Cryptography.DataProtectionScope]::LocalMachine)
            return [System.Text.Encoding]::UTF8.GetString($bytes)
        } catch {
            Write-Log "No se pudo descifrar el secreto DPAPI en $Path (posible otro equipo)." 'WARN'
            return $null
        }
    }
    return $raw
}

function Save-BiometricKeyBackup {
    param([string]$Value)
    if (-not (Test-Path $SecretsDir)) { New-Item -ItemType Directory -Path $SecretsDir -Force | Out-Null }
    $backup = Join-Path $SecretsDir "biometric-key-RESPALDO-NO-BORRAR.txt"
    $lines = @(
        "# ============================================================",
        "#  CONTROL EAT FOOD - CLAVE DE CIFRADO DE HUELLAS (AES-256)",
        "# ============================================================",
        "#  GUARDE ESTE ARCHIVO EN LUGAR SEGURO. NO LO BORRE.",
        "#",
        "#  Esta clave descifra las huellas guardadas en la base de datos.",
        "#  Si reinstala en OTRO equipo (o borra ProgramData), use la opcion",
        "#  'Reinstalacion' y pegue esta clave para que las huellas existentes",
        "#  sigan siendo validas. Si la pierde, habra que re-enrolar todas.",
        "# ============================================================",
        "",
        $Value
    )
    Set-Content -Path $backup -Value $lines -Encoding UTF8
    Write-Log "Respaldo legible de la clave guardado en: $backup" 'WARN'
}

function Get-OrCreateBiometricKey {
    param([switch]$Reinstall)
    $existing = Read-PersistedSecret -Path $BiometricKeyFile
    if ($existing) {
        Write-Log "Clave de cifrado biometrico reutilizada (las huellas existentes seguiran siendo legibles)." 'SUCCESS'
        return $existing
    }
    Write-Log "No se encontro clave persistida en $BiometricKeyFile (o no se pudo descifrar)." 'WARN'

    # El archivo protegido con DPAPI no esta o no se pudo leer. Antes de generar
    # una clave NUEVA (que volveria ilegibles las huellas ya registradas en la
    # BD), se recupera automaticamente del respaldo en texto plano que
    # Save-BiometricKeyBackup guarda junto a la clave cifrada en cada
    # generacion -- asi una reinstalacion reconecta sin pedir nada al usuario.
    $backupPath = Join-Path $SecretsDir "biometric-key-RESPALDO-NO-BORRAR.txt"
    if (Test-Path $backupPath) {
        $backupKey = (Get-Content -Path $backupPath -Encoding UTF8 | Where-Object { $_ -and -not $_.StartsWith('#') } | Select-Object -Last 1).Trim()
        if ($backupKey) {
            Save-PersistedSecret -Path $BiometricKeyFile -Value $backupKey
            Write-Log "Clave de cifrado biometrico recuperada automaticamente desde el respaldo: $backupPath" 'SUCCESS'
            return $backupKey
        }
    }
    if ($Reinstall) {
        Write-Host ""
        Write-Host "  [!] No se encontro la clave de cifrado guardada en este equipo." -ForegroundColor Yellow
        Write-Host "      Si esta base de datos YA tiene huellas, pegue la clave del respaldo" -ForegroundColor Yellow
        Write-Host "      (biometric-key-RESPALDO-NO-BORRAR.txt del equipo anterior)." -ForegroundColor Yellow
        Write-Host "      ENTER en blanco genera una clave NUEVA: las huellas viejas quedaran" -ForegroundColor DarkGray
        Write-Host "      ilegibles y habra que re-enrolarlas." -ForegroundColor DarkGray
        $pasted = (Read-Host "  Clave de cifrado (base64) o ENTER para generar nueva").Trim()
        if ($pasted) {
            Save-PersistedSecret -Path $BiometricKeyFile -Value $pasted
            Write-Log "Clave de cifrado ingresada manualmente y persistida." 'SUCCESS'
            return $pasted
        }
    }
    $key = Generate-RandomBase64 -Bytes 32
    Save-PersistedSecret -Path $BiometricKeyFile -Value $key
    Save-BiometricKeyBackup -Value $key
    Write-Log "Nueva clave de cifrado biometrico generada y persistida en ProgramData." 'SUCCESS'
    return $key
}

function Get-OrCreateJwtSecret {
    $existing = Read-PersistedSecret -Path $JwtSecretFile
    if ($existing) {
        Write-Log "JWT Secret reutilizado (las sesiones existentes siguen validas)." 'SUCCESS'
        return $existing
    }
    $secret = Generate-RandomBase64 -Bytes 32
    Save-PersistedSecret -Path $JwtSecretFile -Value $secret
    Write-Log "Nuevo JWT Secret generado y persistido." 'SUCCESS'
    return $secret
}

# La conexion a BD de produccion (host/puerto/nombre/usuario/password) vivia
# SOLO en RunWindowns\config\install_config.json -- y "Desinstalar" borra esa
# carpeta por completo. Resultado: cada Reinstalacion pedia todos los datos de
# la BD desde cero, con el nombre/usuario en blanco, invitando a errores de
# tipeo que apuntan a una BD distinta (o vacia) donde las huellas ya cifradas
# no sirven de nada. Se guarda una copia en ProgramData (protegida con DPAPI,
# igual que las claves), fuera de config/, para que sobreviva a Desinstalar.
function Save-PersistedDbConfig {
    param([hashtable]$DbConfig)
    $json = $DbConfig | ConvertTo-Json -Compress
    Save-PersistedSecret -Path $DbConfigFile -Value $json
}

function Read-PersistedDbConfig {
    $raw = Read-PersistedSecret -Path $DbConfigFile
    if (-not $raw) { return $null }
    try {
        $obj = $raw | ConvertFrom-Json
        $result = @{}
        $obj.PSObject.Properties | ForEach-Object { $result[$_.Name] = $_.Value }
        return $result
    } catch {
        Write-Log "No se pudo leer la configuracion de BD persistida en $DbConfigFile" 'WARN'
        return $null
    }
}

function Step-ConfigureProduction {
    param([switch]$Reinstall)
    $prodConfig = @{
        JwtSecret = ''; CorsOrigins = ''; PublicUrl = ''; BiometricEncryptionKey = ''
        RateLimitEnabled = 'true'; RateLimitAuth = '10'; RateLimitScan = '60'
        BackendPort = '3000'; ServerIP = (Get-ServerIP)
    }

    # Se pregunta primero la IP del servidor (muy relevante en entornos con
    # VMs / varias redes): recomienda la estatica de la tarjeta fisica si la
    # hay. ENTER acepta la recomendada; si el usuario escribe otra, se usa
    # esa manualmente. El resto de la config (CORS, URL publica, resumen
    # final) depende de esta IP, por eso va al principio del paso.
    $prodConfig.ServerIP = Read-ServerIP -Default $prodConfig.ServerIP
    Write-Log "IP del servidor configurada: $($prodConfig.ServerIP)" 'SUCCESS'

    Write-Host ""
    Write-Host "  --- Puerto ---" -ForegroundColor Yellow
    $prodConfig.BackendPort = "3000"
    Write-Log "Puerto configurado por defecto: $($prodConfig.BackendPort)" 'SUCCESS'

    Write-Host ""
    Write-Host "  --- JWT Secret ---" -ForegroundColor Yellow
    $prodConfig.JwtSecret = Get-OrCreateJwtSecret

    Write-Host ""
    Write-Host "  --- CORS Origins ---" -ForegroundColor Yellow
    # El backend se sirve directo por HTTP en el puerto elegido (entorno interno,
    # sin reverse proxy / HTTPS). El origen CORS incluye el puerto para que la SPA
    # y los clientes (mobile, kioscos) coincidan exactamente con el origen del navegador.
    $defaultCors = "http://$($prodConfig.ServerIP):$($prodConfig.BackendPort),http://localhost:$($prodConfig.BackendPort)"
    $prodConfig.CorsOrigins = $defaultCors
    Write-Log "Origenes CORS configurados por defecto: $($prodConfig.CorsOrigins)" 'SUCCESS'

    Write-Host ""
    Write-Host "  --- URL Publica ---" -ForegroundColor Yellow
    Write-Log "URL publica auto-detectada." 'SUCCESS'

    Write-Host ""
    Write-Host "  --- Biometric Encryption Key (AES-256) ---" -ForegroundColor Yellow
    $prodConfig.BiometricEncryptionKey = Get-OrCreateBiometricKey -Reinstall:$Reinstall

    Write-Host ""
    Write-Host "  --- Rate Limit ---" -ForegroundColor Yellow
    $prodConfig.RateLimitEnabled = 'true'
    Write-Log "Rate Limit habilitado." 'SUCCESS'

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
    # Debe coincidir con el application.yml del backend, porque este archivo lo pisa
    # (--spring.config.additional-location). Flyway es el unico dueno del esquema.
    # baseline-on-migrate queda por compatibilidad con instalaciones creadas por el
    # instalador antiguo, que poblaba la BD con db\*.sql sin dejar historial: sin el,
    # el backend no arranca ("non-empty schema but no schema history table") y NSSM
    # lo reinicia en bucle. NO subir baseline-version al agregar V3, V4...
    baseline-on-migrate: true
    baseline-version: 2
    locations: classpath:db/migration
  jackson:
    time-zone: America/Guayaquil
    serialization:
      write-dates-as-timestamps: false

app:
  public-url: "$($ProdConfig.PublicUrl)"
  security:
    jwt:
      secret: `${JWT_SECRET}
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
    encryption-key: `${BIOMETRIC_ENCRYPTION_KEY}
    native-lib-path: "$($ZkNativePath -replace '\\', '/')"
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
    # ConfigDir se crea al arrancar el script (una sola vez), pero el menu
    # principal corre en bucle dentro del MISMO proceso: si en esta sesion se
    # eligio "Desinstalar" antes (borra RunWindowns\config por completo) y
    # despues se reintenta Instalar/Reparar sin cerrar el instalador, la
    # carpeta ya no existe y WriteAllText falla con ruta no encontrada.
    if (-not (Test-Path $ConfigDir)) { New-Item -ItemType Directory -Path $ConfigDir -Force | Out-Null }
    $utf8NoBom = New-Object System.Text.UTF8Encoding $False
    [System.IO.File]::WriteAllText($ProdYmlPath, $ymlContent, $utf8NoBom)
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

        & mvn clean package -DskipTests "-Dspring.profiles.active=prod" 2>&1 | ForEach-Object {
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

function ConvertFrom-NssmOutput {
    # NSSM escribe su salida en UTF-16LE, pero PowerShell la decodifica con la
    # codepage de la consola (un byte = un caracter), asi que cada letra llega
    # separada por un NUL: "SERVICE_RUNNING" se convierte en "S\0E\0R\0V\0...".
    # Consecuencia real (visible en los logs de instalacion): el
    # "-match 'SERVICE_RUNNING'" NUNCA acertaba y el instalador avisaba
    # "El servicio podria no haber iniciado" aunque el servicio estuviera
    # perfectamente arriba. Esto pasa igual en Windows 10 y en Windows 11.
    param($Raw)
    if ($null -eq $Raw) { return '' }
    return ((($Raw | Out-String) -replace "`0", '').Trim())
}

function Ensure-Nssm {
    # nssm.cc es un unico servidor y responde intermitente (503 "Service Unavailable"
    # observado en pruebas reales, exitoso al reintentar segundos despues). Se reintenta
    # varias veces antes de caer al flujo manual, igual que Install-MavenViaWinget.
    # Si ya esta instalado (Test-Path), devuelve true de inmediato sin volver a descargar.
    if (Test-Path $NssmExe) { return $true }

    $nssmUrl = "https://nssm.cc/release/nssm-2.24.zip"
    $zipPath = Join-Path $NssmDir "nssm.zip"
    $maxAttempts = 3
    for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
        Write-Log "Descargando NSSM (Non-Sucking Service Manager) (intento $attempt de $maxAttempts)..."
        try {
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            Invoke-WebRequest -Uri $nssmUrl -OutFile $zipPath -UseBasicParsing -ErrorAction Stop
            Expand-Archive -Path $zipPath -DestinationPath $NssmDir -Force -ErrorAction Stop
            $foundNssm = Get-ChildItem $NssmDir -Recurse -Filter "nssm.exe" |
                Where-Object { $_.DirectoryName -match 'win64' } | Select-Object -First 1
            if (-not $foundNssm) { $foundNssm = Get-ChildItem $NssmDir -Recurse -Filter "nssm.exe" | Select-Object -First 1 }
            Remove-Item $zipPath -Force -ErrorAction SilentlyContinue
            if ($foundNssm) {
                Copy-Item $foundNssm.FullName $NssmExe -Force
                Write-Log "NSSM descargado: $NssmExe" 'SUCCESS'
                return $true
            }
            Write-Log "El ZIP se descargo pero no se encontro nssm.exe dentro." 'WARN'
        } catch {
            Write-Log "Intento $attempt fallo: $($_.Exception.Message)" 'WARN'
            Remove-Item $zipPath -Force -ErrorAction SilentlyContinue
        }
        if ($attempt -lt $maxAttempts) { Start-Sleep -Seconds 3 }
    }

    Write-Log "No se pudo descargar NSSM automaticamente tras $maxAttempts intentos." 'ERROR'
    Start-Process "https://nssm.cc/download"
    Read-Host "  Presione ENTER cuando NSSM este instalado en $NssmDir"
    if (Test-Path $NssmExe) { return $true }
    Write-Log "NSSM no encontrado en $NssmDir." 'ERROR'
    return $false
}

function Step-ConfigureService {
    param($DbConfig, $ProdConfig)
    Write-Log "Configurando servicio de Windows..." 'STEP'

    $jarFile = Get-ChildItem (Join-Path $BackendDir "target") -Filter "*.jar" |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
    if (-not $jarFile) { Write-Log "No se encontro JAR del backend. No se puede crear servicio." 'ERROR'; return $false }

    if (-not (Ensure-Nssm)) { Write-Log "Servicio no creado (NSSM no disponible)." 'ERROR'; return $false }

    # â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    # Cuenta con la que correrÃ¡ el servicio de Windows.
    #
    # LocalSystem (por defecto) funciona para todo EXCEPTO para abrir el
    # lector biomÃ©trico ZK9500 por JNA/libzkfp: LocalSystem no siempre puede
    # abrir dispositivos USB que requieren interacciÃ³n con el driver del
    # fabricante. Si este servidor va a tener el lector ZK9500 conectado
    # directamente (kiosco o panel de enrolamiento), conviene correr el
    # servicio con una cuenta de usuario real de Windows que haya iniciado
    # sesiÃ³n al menos una vez con el lector enchufado, para que el driver
    # quede cargado en su perfil.
    #
    # NSSM registra la cuenta con su password (necesario para que Windows
    # pueda loguear al usuario al arrancar el servicio automÃ¡ticamente). El
    # password NO se persiste en install_config.json ni en ningÃºn archivo
    # del proyecto: solo se pasa a NSSM/sc.exe en este momento.
    # â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    $serviceAccount = "LocalSystem"
    $servicePassword = $null

    Write-Host ""
    Write-Host "  --- Cuenta del servicio de Windows ---" -ForegroundColor Yellow
    Write-Host "    [1] Una cuenta de usuario de Windows (recomendado si aqui esta conectado el lector)"
    Write-Host "    [2] LocalSystem (por defecto, recomendado si este equipo NO tiene el lector ZK9500)"
    $accountChoice = Read-Choice "Seleccionar opcion" -Max 2

    if ($accountChoice -eq 1) {
        $defaultUser = $env:USERNAME
        do {
            Write-Host ""
            Write-Host "    Importante: debe ser el NOMBRE de usuario (no el PIN de Windows Hello)," -ForegroundColor DarkGray
            Write-Host "    y la CONTRASENA de Windows (no el PIN). Si entras con PIN, necesitas" -ForegroundColor DarkGray
            Write-Host "    crear/recordar tu password real (Ctrl+Alt+Supr -> Cambiar contrasena)." -ForegroundColor DarkGray
            Write-Host ""
            $serviceAccount = Read-Default "Usuario (sin dominio, ej: $defaultUser)" $defaultUser
            $servicePassword = Read-SecureInput "Contrasena de Windows para '$serviceAccount' (se usa solo aqui, no se guarda)"
            $serviceAccount = ".\$serviceAccount"

            # Validar las credenciales antes de registrar el servicio.
            Add-Type -AssemblyName System.DirectoryServices.AccountManagement -ErrorAction SilentlyContinue
            $validated = $false
            try {
                $ctx = New-Object System.DirectoryServices.AccountManagement.PrincipalContext(
                    [System.DirectoryServices.AccountManagement.ContextType]::Machine)
                $validated = $ctx.ValidateCredentials(($serviceAccount -replace '^\.\\',''), $servicePassword)
            } catch { Write-Log "No se pudo validar la cuenta: $($_.Exception.Message)" 'WARN' }

            if (-not $validated) {
                Write-Host "    Las credenciales NO validan contra Windows. El servicio no arrancara." -ForegroundColor Red
                Write-Host "    Verifica usuario/contrasena (recorda: password real, no PIN de Windows Hello)." -ForegroundColor Red
                $retry = Read-Host "    Reintentar? (s/n, ENTER para 's')"
                if ($retry -eq 'n') { Write-Log "Cuenta invalida; volviendo a LocalSystem." 'WARN'; $serviceAccount = "LocalSystem"; $servicePassword = $null; break }
            }
        } until ($validated)

        if ($validated) {
            Write-Log "Cuenta validada: $serviceAccount" 'SUCCESS'
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
    # Sin este control, en un equipo donde Java no quedo en el PATH,
    # (Get-Command java).Source accede a una propiedad de $null y con
    # Set-StrictMode revienta el instalador en vez de dar un mensaje util.
    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    if (-not $javaCmd) {
        Write-Log "No se encontro 'java' en el PATH: no se puede registrar el servicio." 'ERROR'
        Write-Log "Instala Java 21 (o reabre Inicio.bat para refrescar el PATH) y reintenta." 'WARN'
        return $false
    }
    $javaExe = $javaCmd.Source
    $jarPath = $jarFile.FullName

    & $NssmExe install $ServiceName $javaExe "-jar `"$jarPath`" --spring.profiles.active=prod --spring.config.additional-location=file:`"$ProdYmlPath`"" | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Log "Error al registrar servicio." 'ERROR'; return $false }

    # Fijar la cuenta del servicio. NSSM con LocalSystem no lleva password;
    # con un usuario real, NSSM otorga automÃ¡ticamente el derecho
    # "SeServiceLogonRight" y registra el password para el inicio automÃ¡tico.
    if ($serviceAccount -eq "LocalSystem") {
        $setOut = ConvertFrom-NssmOutput (& $NssmExe set $ServiceName ObjectName "LocalSystem" 2>&1)
    } else {
        $setOut = ConvertFrom-NssmOutput (& $NssmExe set $ServiceName ObjectName $serviceAccount $servicePassword 2>&1)
    }
    if ($setOut) { Write-Log $setOut }

    $credentialsFile = Join-Path $ScriptRoot "credenciales.txt"
    & $NssmExe set $ServiceName AppEnvironmentExtra "DB_URL=$($DbConfig.Url)" "DB_USER=$($DbConfig.User)" "DB_PASSWORD=$($DbConfig.Password)" "JWT_SECRET=$($ProdConfig.JwtSecret)" "CORS_ORIGINS=$($ProdConfig.CorsOrigins)" "PUBLIC_URL=$($ProdConfig.PublicUrl)" "BIOMETRIC_ENCRYPTION_KEY=$($ProdConfig.BiometricEncryptionKey)" "RATE_LIMIT_ENABLED=$($ProdConfig.RateLimitEnabled)" "ZK_NATIVE_PATH=$ZkNativePath" "CREDENTIALS_FILE=$credentialsFile" | Out-Null
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

    # Rotacion de los archivos stdout/stderr capturados por NSSM. La app
    # escribe su log principal via Logback a backend/logs/application.log
    # (con su propia rotacion diaria + 50MB), pero NSSM sigue capturando
    # el banner de arranque, errores nativos de JVM/Flyway previos a Logback
    # y cualquier System.out. Sin rotacion estos archivos crecen sin limite.
    # AppRotateBytes=10MB + AppRotateSeconds=24h corta y respalda como
    # stdout.log.N o stdout.log.TIMESTAMP (modo append, conserva sesion).
    & $NssmExe set $ServiceName AppRotateFiles   1       | Out-Null
    & $NssmExe set $ServiceName AppRotateOnline  1       | Out-Null
    & $NssmExe set $ServiceName AppRotateBytes   10485760 | Out-Null
    & $NssmExe set $ServiceName AppRotateSeconds 86400   | Out-Null
    Clean-ServiceLogs -ServiceLogsDir $serviceLogsDir

    Write-Log "Iniciando servicio '$ServiceName'..."
    & $NssmExe start $ServiceName | Out-Null
    Start-Sleep -Seconds 5

    $status = ConvertFrom-NssmOutput (& $NssmExe status $ServiceName 2>&1)
    if ($status -match 'SERVICE_RUNNING') {
        Write-Log "Servicio '$ServiceName' iniciado correctamente." 'SUCCESS'
        if ($serviceAccount -ne "LocalSystem") {
            Write-Log "Cuenta del servicio: $serviceAccount (lector ZK9500 deberia poder abrirse)." 'SUCCESS'
        }
    } else {
        Write-Log "El servicio podria no haber iniciado. Estado: $status" 'WARN'; Write-Log "Revisar logs en: $serviceLogsDir" 'WARN'
        if ($serviceAccount -ne "LocalSystem") {
            Write-Log "Si el error es 'inicio de sesion', revisa usuario/password (no PIN)." 'WARN'
            Write-Log "Volve a ejecutar la instalacion y proba con LocalSystem si no tenes el password." 'WARN'
        }
    }

    return $true
}

# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
# FIREWALL -- expone el puerto del backend directamente a la LAN
# (entorno interno sin HTTPS / reverse proxy). El puerto lo define
# $ProdConfig.BackendPort en la instancia en curso.
# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
function Step-ConfigureFirewall {
    param($ProdConfig)
    Write-Log "Configurando firewall de Windows..." 'STEP'

    # Limpia reglas obsoletas de instalaciones anteriores que usaban Caddy
    # en los puertos 80/443 -- ya no aplican porque no hay reverse proxy.
    foreach ($p in @(443, 80)) {
        $oldName = "ControlEatFood (Port $p)"
        if (Get-NetFirewallRule -DisplayName $oldName -ErrorAction SilentlyContinue) {
            try {
                Remove-NetFirewallRule -DisplayName $oldName -ErrorAction Stop
                Write-Log "Regla de firewall obsoleta eliminada: $oldName" 'SUCCESS'
            } catch { Write-Log "No se pudo eliminar regla obsoleta $oldName." 'WARN' }
        }
    }

    $ruleName = "ControlEatFood (Port $($ProdConfig.BackendPort))"
    if (Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue) {
        Write-Log "Regla de firewall ya existe: $ruleName" 'SUCCESS'
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
    param([switch]$Reinstall)
    Write-Host ""
    Write-Host "  ============================================================" -ForegroundColor Green
    Write-Host "  $(if($Reinstall){'REINSTALACION (conservando datos y claves)'}else{'INICIANDO INSTALACION DE PRODUCCION'})" -ForegroundColor Green
    Write-Host "  ============================================================" -ForegroundColor Green
    if ($Reinstall) {
        Write-Host "  Se conservaran las claves de cifrado existentes para que las" -ForegroundColor Cyan
        Write-Host "  huellas ya registradas sigan siendo validas." -ForegroundColor Cyan
    }

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
    $prodConfig = Step-ConfigureProduction -Reinstall:$Reinstall

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
        $jarFile = Get-ChildItem (Join-Path $BackendDir "target") -Filter "*.jar" | Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
        if ($jarFile) {
            $jarPath = $jarFile.FullName
            & $NssmExe set $ServiceName AppParameters "-jar `"$jarPath`" --spring.profiles.active=prod --spring.config.additional-location=file:`"$ProdYmlPath`"" | Out-Null
        }
        & $NssmExe start $ServiceName 2>&1 | Out-Null
        Start-Sleep -Seconds 3
        $status = ConvertFrom-NssmOutput (& $NssmExe status $ServiceName 2>&1)
        if ($status -match 'SERVICE_RUNNING') { Write-Log "Servicio reiniciado y corriendo. Estado: $status" 'SUCCESS' }
        else { Write-Log "El servicio no reporta SERVICE_RUNNING. Estado: $status" 'WARN' }
    }
    Write-Log "Actualizacion completada." 'SUCCESS'
}

function Repair-App {
    Show-Menu "Que deseas reparar?" @(
        "Reconectar base de datos", "Regenerar application-prod.yml",
        "Reinstalar servicio Windows", "Reconfigurar firewall",
        "Volver al menu"
    )
    $choice = Read-Choice "Seleccionar" -Max 5
    if ($choice -eq 5) { return }

    $config = $null
    if (Test-Path $ConfigFile) {
        try { $config = Get-Content $ConfigFile -Raw -Encoding UTF8 | ConvertFrom-Json }
        catch { Write-Log "install_config.json ilegible o corrupto: $($_.Exception.Message)" 'WARN'; $config = $null }
    }

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
        try {
            $config = Get-Content $ConfigFile -Raw -Encoding UTF8 | ConvertFrom-Json
            Write-Log "Configuracion encontrada: $($config.installDate)"
        } catch {
            Write-Log "install_config.json ilegible; se continua con desinstalacion generica." 'WARN'
            $config = $null
        }
    } else {
        Write-Log "No se encontro config previa. Desinstalacion generica." 'WARN'
    }

    Write-Host ""
    Write-Host "  Esta accion desinstala TODO con una sola confirmacion:" -ForegroundColor Yellow
    Write-Host "    1. Detener y eliminar el servicio '$ServiceName'"
    Write-Host "       (y el servicio obsoleto 'ControlEatFoodProxy' de Caddy si aun existe)"
    Write-Host "    2. Eliminar reglas de firewall"
    Write-Host "    3. Eliminar archivos compilados (target/, dist/, node_modules/)"
    Write-Host "    4. Eliminar la base de datos local (contenedor Docker 'control-mysql', si existe)"
    Write-Host "    5. Eliminar archivos de configuracion (config/)"
    Write-Host ""
    Write-Host "  ADVERTENCIA: la base de datos local se elimina CON TODOS SUS DATOS." -ForegroundColor Red
    Write-Host ""
    if ((Read-Host "  Desea desinstalar todo? (s/n)") -ne 's') { Write-Log "Desinstalacion cancelada."; return }

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

    # Limpieza defensiva: instalaciones previas (instalador con Caddy) pudieron
    # registrar el servicio 'ControlEatFoodProxy'. Aunque la opcion de instalarlo
    # ya no existe en el instalador, lo eliminamos aqui si sigue presente, para
    # dejar el equipo limpio al desinstalar.
    $legacyProxyService = "ControlEatFoodProxy"
    if (Get-Service -Name $legacyProxyService -ErrorAction SilentlyContinue) {
        Write-Log "Deteniendo servicio obsoleto '$legacyProxyService'..."
        if (Test-Path $NssmExe) {
            & $NssmExe stop $legacyProxyService confirm 2>&1 | Out-Null
            Start-Sleep -Seconds 2
            & $NssmExe remove $legacyProxyService confirm 2>&1 | Out-Null
        } else {
            & sc.exe stop $legacyProxyService 2>&1 | Out-Null
            & sc.exe delete $legacyProxyService 2>&1 | Out-Null
        }
        Write-Log "Servicio obsoleto de proxy HTTPS (Caddy) detenido y eliminado." 'SUCCESS'
    }

    Write-Log "Eliminando reglas de firewall..."
    $ruleNames = @("ControlEatFood (Port 3000)", "ControlEatFood (Port 8080)", "ControlEatFood (Port 443)", "ControlEatFood (Port 80)")
    if ($config) { $ruleNames += "ControlEatFood (Port $($config.production.backendPort))" }
    foreach ($ruleName in ($ruleNames | Select-Object -Unique)) {
        if (Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue) {
            Remove-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
            Write-Log "Regla eliminada: $ruleName" 'SUCCESS'
        }
    }

    Write-Log "Eliminando archivos compilados..."
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

    # Base de datos local: se elimina el contenedor Docker 'control-mysql' si
    # existe (con sus datos). Para un MySQL instalado localmente no se toca la
    # BD (no hay forma segura de hacerlo sin credenciales): se indica el DROP.
    $dockerContainer = $false
    try { $containers = & docker ps -a --format "{{.Names}}" 2>&1; if ($containers -match 'control-mysql') { $dockerContainer = $true } } catch { }
    if ($dockerContainer) {
        Write-Log "Eliminando contenedor Docker 'control-mysql' (incluye la base de datos)..."
        & docker stop control-mysql 2>&1 | Out-Null
        & docker rm control-mysql 2>&1 | Out-Null
        Write-Log "Contenedor Docker 'control-mysql' eliminado." 'SUCCESS'
    } elseif ($config -and ($config.PSObject.Properties['database']) -and ($config.database.PSObject.Properties['Type']) -and $config.database.Type -eq 'local') {
        Write-Log "MySQL local sin contenedor: elimina la BD manualmente con: DROP DATABASE $($config.database.Name);" 'WARN'
    }

    if (Test-Path $ConfigDir) {
        Remove-Item -Path $ConfigDir -Recurse -Force
        Write-Log "Directorio config/ eliminado." 'SUCCESS'
    }

    # Eliminar el marcador local del SDK biométrico para que una reinstalación vuelva
    # a VERIFICAR el SDK. Nota: setup.exe solo se re-ejecutará si libzkfp.dll ya no está
    # en System32; si sigue instalada (lo normal, el SDK es de por vida y sobrevive a la
    # desinstalación de la app), Invoke-BiometricSetup lo detecta y omite setup.exe.
    if (Test-Path $LockFile) {
        Remove-Item -Path $LockFile -Force
        Write-Log "Lock del SDK biometrico (.setup_completado.lock) eliminado." 'SUCCESS'
    }

    # La clave de cifrado (ProgramData\ControlEatFood) se CONSERVA a proposito: si
    # la BD sobrevive, permite reinstalar sin invalidar las huellas ya registradas.
    if (Test-Path $BiometricKeyFile) {
        Write-Host ""
        Write-Host "  NOTA: la clave de cifrado de huellas se conservo en:" -ForegroundColor Cyan
        Write-Host "        $SecretsDir" -ForegroundColor Cyan
        Write-Host "        (asi una reinstalacion no invalida las huellas existentes)." -ForegroundColor DarkGray
        Write-Log "Clave de cifrado conservada en $SecretsDir (no se borra al desinstalar)." 'WARN'
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
        $svcStatus = ConvertFrom-NssmOutput (& $NssmExe status $ServiceName 2>&1)
        # Si el servicio no existe, NSSM devuelve un volcado de varias lineas
        # ("Can't open service!..."). Se resume en una sola linea legible.
        if ($svcStatus -notmatch 'SERVICE_') { $svcStatus = 'NO INSTALADO' }
        Write-Host "    Servicio:     $svcStatus" -ForegroundColor $(if ($svcStatus -match 'RUNNING') { 'Green' } else { 'Yellow' })
    }
    if (Test-Path $ConfigFile) { Write-Host "    Config:       EXISTE" -ForegroundColor Green } else { Write-Host "    Config:       NO EXISTE" -ForegroundColor Red }

    Write-Host ""
    Write-Host "  --- Puertos en escucha ---" -ForegroundColor Yellow
    foreach ($port in @(3000, 8080, 80, 3306, 443)) {
        if (Test-TcpPort -HostName 'localhost' -Port $port) { Write-Host "    Puerto $port :  ABIERTO" -ForegroundColor Green }
        else { Write-Host "    Puerto $port :  CERRADO" -ForegroundColor DarkGray }
    }

    $jarExists = Get-ChildItem (Join-Path $BackendDir "target") -Filter "*.jar" -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch 'sources|javadoc' }
    if ($jarExists) { Write-Host "    JAR Backend:  $($jarExists.Name)" -ForegroundColor Green } else { Write-Host "    JAR Backend:  NO GENERADO" -ForegroundColor Red }
    if (Test-Path (Join-Path $FrontendDir "dist")) { Write-Host "    Frontend:     dist/ EXISTE" -ForegroundColor Green } else { Write-Host "    Frontend:     dist/ NO EXISTE" -ForegroundColor Red }
    Write-Host ""
}

# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
# MENU PRINCIPAL (unificado: Pruebas + Produccion en un solo menu)
# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
function Show-MainMenu {
    $salir = $false
    while (-not $salir) {
        Clear-Host
        Write-Banner
        
        $options = @(
            "Pruebas (entorno de desarrollo local)",
            "Instalacion nueva (Produccion)",
            "Reinstalacion (conservar datos y claves existentes)",
            "Actualizar aplicacion (rebuild + restart)",
            "Reparar configuracion",
            "Desinstalar",
            "Diagnosticos del entorno",
            "Salir"
        )
        
        Show-Menu "Que deseas hacer?" $options
        
        $selection = Read-Choice "Seleccionar opcion" -Max 8

        $requiresAdmin = $selection -ge 2 -and $selection -le 7
        
        if ($requiresAdmin -and -not (Test-IsAdmin)) {
            Write-Host ""
            Write-Host "  [!] Esta opcion requiere permisos de Administrador." -ForegroundColor Yellow
            Write-Host "      Usa Inicio.bat (doble clic) en lugar de ejecutar Inicio.ps1 directamente." -ForegroundColor Yellow
            Write-Host "      El .bat solicita elevacion UAC automaticamente al abrir." -ForegroundColor DarkGray
            Write-Host ""
            Read-Host "  Presione ENTER para continuar"
            continue
        }
        
        # Cada opcion va en su propio try/catch: antes, cualquier error no
        # controlado dentro de una opcion subia hasta el try de INICIO, imprimia
        # "Error inesperado" y CERRABA el instalador, obligando a relanzarlo y a
        # reescribir toda la configuracion. Ahora el error se reporta y se vuelve
        # al menu, que es lo util para quien esta instalando en sitio.
        try {
            switch ($selection) {
                1 { Start-TestSetup }
                2 { Install-Full }
                3 { Install-Full -Reinstall }
                4 { Update-App }
                5 { Repair-App }
                6 { Uninstall-App }
                7 { Show-Diagnostics }
                8 {
                    Write-Host "  Saliendo... Hasta luego!" -ForegroundColor Yellow
                    $salir = $true
                }
            }
        } catch {
            Write-Log "La opcion $selection fallo: $_" 'ERROR'
            Write-Log "Detalle: $($_.ScriptStackTrace)" 'ERROR'
            Write-Host ""
            Write-Host "  El instalador sigue abierto: podes reintentar u elegir otra opcion." -ForegroundColor Yellow
            Write-Host "  El detalle completo quedo en: $LogFile" -ForegroundColor DarkGray
        }
        
        if (-not $salir) {
            Write-Host ""
            Read-Host "  Presione ENTER para continuar"
        }
    }
}

# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
# INICIO
# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
try {
    # Limpieza baseline de logs de servicio cada vez que se abre el instalador.
    # Programa rotacion de NSSM en logs/service/ â†’ stdout.log.N, stdout.log.(N+1)...
    # Sin esto, los archivos crecen indefinidamente entre reinstalaciones.
    Clean-ServiceLogs -ServiceLogsDir (Join-Path $LogsDir "service")

    Show-MainMenu
} catch {
    Write-Log "Error inesperado: $_" 'ERROR'
    Write-Log "Stack: $($_.ScriptStackTrace)" 'ERROR'
    Read-Host "  Presione ENTER para salir"
}
