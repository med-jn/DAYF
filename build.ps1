# Tik Guest: local build. Bumps versionCode, then builds the APK (sideload) and the AAB (Google Play) into dist\.
# Usage:  .\build.ps1          (bump + build)
#         .\build.ps1 -NoBump  (build without bumping)
param([switch]$NoBump)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not (Test-Path "local.properties")) {
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
    if (-not $sdk -and (Test-Path "$env:LOCALAPPDATA\Android\Sdk")) { $sdk = "$env:LOCALAPPDATA\Android\Sdk" }
    if (-not $sdk) { throw "Android SDK not found. Install Android Studio or set ANDROID_HOME." }
    "sdk.dir=" + ($sdk -replace '\\', '/') | Set-Content "local.properties"
}

$vp = "version.properties"
$code = [int](((Get-Content $vp) | Where-Object { $_ -match '^versionCode=' }) -replace 'versionCode=', '')
if (-not $NoBump) { $code++; "versionCode=$code" | Set-Content $vp }
Write-Host "== Tik Guest 1.0.$code ==" -ForegroundColor Cyan

& .\gradlew.bat assembleDebug bundleRelease --console=plain -PskipBump=1
if ($LASTEXITCODE -ne 0) { throw "Build failed" }

New-Item -ItemType Directory -Force dist | Out-Null
Copy-Item "app\build\outputs\apk\debug\app-debug.apk" "dist\TikGuest-1.0.$code.apk" -Force
Copy-Item "app\build\outputs\bundle\release\app-release.aab" "dist\TikGuest-1.0.$code.aab" -Force
Write-Host "Done:" -ForegroundColor Green
Get-ChildItem dist | Sort-Object LastWriteTime -Descending | Select-Object -First 2 Name, Length
