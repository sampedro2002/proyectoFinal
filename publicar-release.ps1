<#
.SYNOPSIS
  Publica o actualiza un Release en GitHub con el APK y setup.exe.

.DESCRIPTION
  Uso:
    .\publicar-release.ps1              → Actualiza el release más reciente
    .\publicar-release.ps1 v1.2.0       → Crea un release nuevo con ese tag
    .\publicar-release.ps1 v1.1.0 -Solo apk   → Sube solo el APK
    .\publicar-release.ps1 v1.1.0 -Solo exe   → Sube solo el setup.exe

.EXAMPLE
  .\publicar-release.ps1
  .\publicar-release.ps1 v2.0.0
#>
param(
    [string]$Version,
    [ValidateSet("apk","exe","todos")]
    [string]$Solo = "todos"
)

$ErrorActionPreference = "Stop"
$repo = "sampedro2002/proyectoFinal"
$apkPath = "APKAndriodInstall\Instalar.apk"
$exePath = "RunWindowns\setup.exe"

# ── Verificar que gh está instalado ──
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    Write-Host "❌ Necesitas instalar GitHub CLI (gh). https://cli.github.com/" -ForegroundColor Red
    exit 1
}

# ── Determinar archivos a subir ──
$archivos = @()
if ($Solo -eq "todos" -or $Solo -eq "apk") {
    if (Test-Path $apkPath) { $archivos += $apkPath }
    else { Write-Host "⚠️  No se encontró $apkPath" -ForegroundColor Yellow }
}
if ($Solo -eq "todos" -or $Solo -eq "exe") {
    if (Test-Path $exePath) { $archivos += $exePath }
    else { Write-Host "⚠️  No se encontró $exePath" -ForegroundColor Yellow }
}

if ($archivos.Count -eq 0) {
    Write-Host "❌ No hay archivos para subir." -ForegroundColor Red
    exit 1
}

# Mostrar tamaños
foreach ($f in $archivos) {
    $sizeMB = [math]::Round((Get-Item $f).Length / 1MB, 2)
    Write-Host "📦 $f → $sizeMB MB" -ForegroundColor Cyan
}

# ── Si no se dio versión, actualizar el release más reciente ──
if (-not $Version) {
    $Version = gh release list --repo $repo --limit 1 --json tagName --jq '.[0].tagName' 2>$null
    if (-not $Version) {
        Write-Host "❌ No hay releases previos. Especifica una versión: .\publicar-release.ps1 v1.0.0" -ForegroundColor Red
        exit 1
    }
    Write-Host "`n🔄 Actualizando release existente: $Version" -ForegroundColor Yellow

    # Borrar assets viejos con el mismo nombre y subir los nuevos
    foreach ($f in $archivos) {
        $nombre = Split-Path $f -Leaf
        Write-Host "   Reemplazando $nombre..." -ForegroundColor Gray
        # Intentar borrar el asset viejo (si existe)
        $assetId = gh api "repos/$repo/releases/tags/$Version" --jq ".assets[] | select(.name==\""$nombre\"") | .id" 2>$null
        if ($assetId) {
            gh api -X DELETE "repos/$repo/releases/assets/$assetId" 2>$null | Out-Null
        }
    }
    gh release upload $Version @archivos --repo $repo --clobber
}
else {
    # ── Crear release nuevo ──
    $existingTag = git tag -l $Version
    if (-not $existingTag) {
        Write-Host "`n🏷️  Creando tag $Version..." -ForegroundColor Cyan
        git tag -a $Version -m "Release $Version"
        git push origin $Version
    }

    Write-Host "🚀 Creando release $Version..." -ForegroundColor Green
    gh release create $Version @archivos `
        --repo $repo `
        --title "Release $Version" `
        --notes "## 📱 App Móvil Android`nDescarga **Instalar.apk** e instálalo en tu dispositivo Android.`n`n## 🖥️ Instalador Windows`nDescarga **setup.exe** y ejecútalo para instalar.`n`n---`n_Release $Version_"
}

Write-Host "`n✅ ¡Listo! Release disponible en:" -ForegroundColor Green
Write-Host "   https://github.com/$repo/releases/tag/$Version" -ForegroundColor White
