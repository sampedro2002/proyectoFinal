<#
.SYNOPSIS
  Publica o actualiza el APK en un Release de GitHub.

.DESCRIPTION
  Uso:
    .\publicar-release.ps1              → Actualiza el release más reciente
    .\publicar-release.ps1 v1.2.0       → Crea un release nuevo con ese tag

.EXAMPLE
  .\publicar-release.ps1
  .\publicar-release.ps1 v2.0.0
#>
param(
    [string]$Version
)

$ErrorActionPreference = "Stop"
$repo = "sampedro2002/proyectoFinal"
$apkPath = "APKAndriodInstall\Instalar.apk"

# ── Verificar que gh está instalado ──
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    Write-Host "❌ Necesitas instalar GitHub CLI (gh). https://cli.github.com/" -ForegroundColor Red
    exit 1
}

# ── Verificar que existe el APK ──
if (-not (Test-Path $apkPath)) {
    Write-Host "❌ No se encontró $apkPath" -ForegroundColor Red
    exit 1
}

$sizeMB = [math]::Round((Get-Item $apkPath).Length / 1MB, 2)
Write-Host "📦 $apkPath → $sizeMB MB" -ForegroundColor Cyan

# ── Si no se dio versión, actualizar el release más reciente ──
if (-not $Version) {
    $Version = gh release list --repo $repo --limit 1 --json tagName --jq '.[0].tagName' 2>$null
    if (-not $Version) {
        Write-Host "❌ No hay releases previos. Especifica una versión: .\publicar-release.ps1 v1.0.0" -ForegroundColor Red
        exit 1
    }
    Write-Host "`n🔄 Actualizando release existente: $Version" -ForegroundColor Yellow
    gh release upload $Version $apkPath --repo $repo --clobber
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
    gh release create $Version $apkPath `
        --repo $repo `
        --title "Release $Version" `
        --notes "## 📱 App Móvil Android`nDescarga **Instalar.apk** e instálalo en tu dispositivo Android.`n`n---`n_Release $Version_"
}

Write-Host "`n✅ ¡Listo! Release disponible en:" -ForegroundColor Green
Write-Host "   https://github.com/$repo/releases/tag/$Version" -ForegroundColor White
