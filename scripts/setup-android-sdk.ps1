<#
.SYNOPSIS
  Bootstraps a local, CLI-only Android SDK for building this project without Android Studio.

.DESCRIPTION
  Downloads the Android command-line tools, installs platform-tools, the Android 34
  platform, and build-tools 34.0.0 into .cli-tools/android-sdk, accepts the SDK
  licenses, and writes local.properties. After running this once, use the Gradle
  wrapper (.\gradlew.bat) to build - it downloads Gradle itself on first run.

.EXAMPLE
  .\scripts\setup-android-sdk.ps1
#>

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$cliTools = Join-Path $root ".cli-tools"
$sdkRoot = Join-Path $cliTools "android-sdk"
$cmdlineZip = Join-Path $cliTools "cmdline-tools.zip"

New-Item -ItemType Directory -Force -Path $cliTools | Out-Null

if (-not (Test-Path (Join-Path $sdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"))) {
    Write-Host "Downloading Android command-line tools..."
    $cmdlineToolsUrl = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
    Invoke-WebRequest -Uri $cmdlineToolsUrl -OutFile $cmdlineZip -UseBasicParsing

    $extractDir = Join-Path $cliTools "extract-sdk"
    Expand-Archive -Path $cmdlineZip -DestinationPath $extractDir -Force
    New-Item -ItemType Directory -Force -Path (Join-Path $sdkRoot "cmdline-tools") | Out-Null
    Move-Item -Path (Join-Path $extractDir "cmdline-tools") -Destination (Join-Path $sdkRoot "cmdline-tools\latest") -Force
    Remove-Item $extractDir -Recurse -Force
    Remove-Item $cmdlineZip -Force
} else {
    Write-Host "Android command-line tools already present, skipping download."
}

$sdkmanager = Join-Path $sdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"
$sdkRootAbs = (Resolve-Path $sdkRoot).Path

Write-Host "Accepting SDK licenses..."
$yesBlock = (@("y") * 12) -join "`n"
$yesBlock | & $sdkmanager --sdk_root="$sdkRootAbs" --licenses | Out-Null

Write-Host "Installing platform-tools, platforms;android-34, build-tools;34.0.0..."
& $sdkmanager --sdk_root="$sdkRootAbs" "platform-tools" "platforms;android-34" "build-tools;34.0.0" | Out-Null

$sdkDirForward = $sdkRootAbs -replace '\\', '/'
"sdk.dir=$sdkDirForward" | Set-Content -Path (Join-Path $root "local.properties") -Encoding ASCII

Write-Host ""
Write-Host "Done. SDK installed at: $sdkRootAbs"
Write-Host "Next steps:"
Write-Host "  .\gradlew.bat assembleDebug"
Write-Host "  .cli-tools\android-sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk"
