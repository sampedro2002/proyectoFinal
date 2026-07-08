#Requires -RunAsAdministrator
<#
.SYNOPSIS
    ControlEatFood - Desinstalador para Windows Server
.DESCRIPTION
    Elimina el servicio, firewall, y opcionalmente archivos y base de datos.
.NOTES
    Ejecutar como Administrador.
#>

[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ScriptRoot
$ConfigDir = Join-Path $ScriptRoot "config"
$LogsDir = Join-Path $ScriptRoot "logs"
$LogFile = Join-Path $LogsDir ("uninstall_{0}.log" -f (Get-Date -Format "yyyyMMdd_HHmmss"))
$ConfigFile = Join-Path $ConfigDir "install_config.json"
$NssmDir = Join-Path $ScriptRoot "tools"
$NssmExe = Join-Path $NssmDir "nssm.exe"
$ServiceName = "ControlEatFood"

@($LogsDir) | ForEach-Object {
    if (-not (Test-Path $_)) { New-Item -ItemType Directory -Path $_ -Force | Out-Null }
}

function Write-Log {
    param([string]$Message, [string]$Level = 'INFO')
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $line = "[$timestamp] [$Level] $Message"
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
    switch ($Level) {
        'ERROR'   { Write-Host "  [X] $Message" -ForegroundColor Red }
        'WARN'    { Write-Host "  [!] $Message" -ForegroundColor Yellow }
        'SUCCESS' { Write-Host "  [OK] $Message" -ForegroundColor Green }
        default   { Write-Host "  $Message" }
    }
}

Write-Host ""
Write-Host "======================================================================" -ForegroundColor Red
Write-Host "     CONTROL EAT FOOD - DESINSTALADOR" -ForegroundColor Red
Write-Host "======================================================================" -ForegroundColor Red
Write-Host ""

# Verificar admin
$currentPrincipal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $currentPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Log "Se requieren privilegios de administrador." 'ERROR'
    Read-Host "  Presione ENTER para salir"
    exit 1
}

# Cargar config si existe
$config = $null
if (Test-Path $ConfigFile) {
    $config = Get-Content $ConfigFile | ConvertFrom-Json
    Write-Log "Configuracion encontrada: $($config.installDate)"
    Write-Log "  DB: $($config.database.Host):$($config.database.Port)/$($config.database.Name)"
    Write-Log "  Backend port: $($config.production.backendPort)"
} else {
    Write-Log "No se encontro config previa. Desinstalacion generica." 'WARN'
}

Write-Host ""
Write-Host "  Se realizaran las siguientes acciones:" -ForegroundColor Yellow
Write-Host "    1. Detener servicio '$ServiceName'"
Write-Host "    2. Eliminar servicio '$ServiceName'"
Write-Host "    3. Eliminar reglas de firewall"
Write-Host "    4. Opcionalmente: eliminar archivos compilados"
Write-Host "    5. Opcionalmente: eliminar base de datos (si es local)"
Write-Host ""

$confirm = Read-Host "  Desea continuar? (s/n)"
if ($confirm -ne 's') {
    Write-Log "Desinstalacion cancelada."
    exit 0
}

# 1. Detener servicio
Write-Log "Deteniendo servicio '$ServiceName'..."
if (Test-Path $NssmExe) {
    & $NssmExe stop $ServiceName confirm 2>&1 | Out-Null
    Start-Sleep -Seconds 2
    Write-Log "Servicio detenido." 'SUCCESS'
} else {
    Write-Log "NSSM no encontrado. Intentando con sc.exe..." 'WARN'
    & sc.exe stop $ServiceName 2>&1 | Out-Null
}

# 2. Eliminar servicio
Write-Log "Eliminando servicio '$ServiceName'..."
if (Test-Path $NssmExe) {
    & $NssmExe remove $ServiceName confirm 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Log "Servicio eliminado." 'SUCCESS'
    } else {
        Write-Log "Posible error al eliminar servicio (puede que no existiera)." 'WARN'
    }
} else {
    & sc.exe delete $ServiceName 2>&1 | Out-Null
    Write-Log "Servicio eliminado via sc.exe." 'SUCCESS'
}

# 3. Eliminar reglas de firewall
Write-Log "Eliminando reglas de firewall..."
$ruleNames = @(
    "ControlEatFood Backend (Port 8080)",
    "ControlEatFood Backend (Port $($config.production.backendPort))",
    "ControlEatFood Frontend (Port 80)",
    "ControlEatFood Frontend (Port $($config.production.frontendPort))"
)
foreach ($ruleName in ($ruleNames | Select-Object -Unique)) {
    $existing = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
    if ($existing) {
        Remove-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
        Write-Log "Regla eliminada: $ruleName" 'SUCCESS'
    }
}

# 4. Eliminar archivos compilados (opcional)
Write-Host ""
$cleanFiles = Read-Host "  Eliminar archivos compilados (target/, dist/, node_modules/)? (s/n)"
if ($cleanFiles -eq 's') {
    $backendDir = Join-Path $ProjectRoot "controlEatFoodWeb\backend"
    $frontendDir = Join-Path $ProjectRoot "controlEatFoodWeb\frontend"

    # Backend target
    $targetDir = Join-Path $backendDir "target"
    if (Test-Path $targetDir) {
        Remove-Item -Path $targetDir -Recurse -Force
        Write-Log "Eliminado: $targetDir" 'SUCCESS'
    }

    # Frontend dist
    $distDir = Join-Path $frontendDir "dist"
    if (Test-Path $distDir) {
        Remove-Item -Path $distDir -Recurse -Force
        Write-Log "Eliminado: $distDir" 'SUCCESS'
    }

    # Frontend node_modules
    $nmDir = Join-Path $frontendDir "node_modules"
    if (Test-Path $nmDir) {
        Write-Log "Eliminando node_modules (puede tardar)..."
        Remove-Item -Path $nmDir -Recurse -Force
        Write-Log "Eliminado: $nmDir" 'SUCCESS'
    }

    # application-prod.yml en resources
    $prodYmlInResources = Join-Path $backendDir "src\main\resources\application-prod.yml"
    if (Test-Path $prodYmlInResources) {
        Remove-Item -Path $prodYmlInResources -Force
        Write-Log "Eliminado: application-prod.yml del backend" 'SUCCESS'
    }
}

# 5. Eliminar base de datos (opcional, solo si es local)
if ($config -and $config.database.Type -eq 'local') {
    Write-Host ""
    $cleanDb = Read-Host "  Eliminar base de datos local '$($config.database.Name)'? (s/n)"
    if ($cleanDb -eq 's') {
        # Verificar si es Docker
        $dockerContainer = $false
        try {
            $containers = & docker ps -a --format "{{.Names}}" 2>&1
            if ($containers -match 'control-mysql') { $dockerContainer = $true }
        } catch { }

        if ($dockerContainer) {
            $removeContainer = Read-Host "  Tambien eliminar contenedor Docker 'control-mysql'? (s/n)"
            if ($removeContainer -eq 's') {
                & docker stop control-mysql 2>&1 | Out-Null
                & docker rm control-mysql 2>&1 | Out-Null
                Write-Log "Contenedor Docker 'control-mysql' eliminado." 'SUCCESS'
            } else {
                Write-Log "Contenedor Docker conservado." 'WARN'
            }
        } else {
            Write-Log "Eliminar manualmente la base de datos '$($config.database.Name)' desde MySQL." 'WARN'
            Write-Log "  DROP DATABASE $($config.database.Name);" 'WARN'
        }
    }
}

# 6. Eliminar config (opcional)
Write-Host ""
$cleanConfig = Read-Host "  Eliminar archivos de configuracion (config/)? (s/n)"
if ($cleanConfig -eq 's') {
    if (Test-Path $ConfigDir) {
        Remove-Item -Path $ConfigDir -Recurse -Force
        Write-Log "Directorio config/ eliminado." 'SUCCESS'
    }
}

# Resumen
Write-Host ""
Write-Host "  ============================================================" -ForegroundColor Green
Write-Host "  DESINSTALACION COMPLETADA" -ForegroundColor Green
Write-Host "  ============================================================" -ForegroundColor Green
Write-Host ""
Write-Host "  Log: $LogFile" -ForegroundColor DarkGray
Write-Host ""
Read-Host "  Presione ENTER para salir"
