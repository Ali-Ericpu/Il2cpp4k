<#
.SYNOPSIS
  把 il2cpp4k 注入器与框架库推到 Android 设备并在 root 下执行。

.EXAMPLE
  .\tools\inject-android.ps1 -Action list

.EXAMPLE
  .\tools\inject-android.ps1 -Action inject -Lib .\api\build\bin\androidNativeArm64\releaseShared\libil2cpp4k.so -Target com.example.game

.NOTES
  仅限自有设备调试；需要 root（adb shell su）。
#>
param(
    [Parameter(Mandatory = $true)][ValidateSet('list', 'inject')][string]$Action,
    [string]$Lib,
    [string]$Target,
    [string]$Entry = 'il2cpp4k_init',
    [string]$Serial,
    [switch]$CallEntry
)

$ErrorActionPreference = 'Stop'
$remoteDir = '/data/local/tmp'
$injectorLocal = Join-Path $PSScriptRoot '..\injector\build\bin\androidNativeArm64\releaseExecutable\il2cpp4k-inject.kexe'

$adbArgs = @()
if ($Serial) { $adbArgs += @('-s', $Serial) }

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { throw 'adb not found in PATH' }
$devices = & adb @adbArgs devices | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice' }
if (-not $devices) { throw 'no adb device connected (use -Serial to pick one)' }

if (-not (Test-Path $injectorLocal)) {
    throw "injector binary not found: $injectorLocal — run: .\gradlew.bat :injector:linkReleaseExecutableAndroidNativeArm64"
}

Write-Host 'pushing injector...'
& adb @adbArgs push $injectorLocal "$remoteDir/il2cpp4k-inject" | Out-Null

if ($Action -eq 'inject') {
    if (-not $Lib) { throw '-Lib is required for inject' }
    if (-not (Test-Path $Lib)) { throw "library not found: $Lib" }
    Write-Host "pushing $Lib..."
    & adb @adbArgs push $Lib "$remoteDir/$(Split-Path $Lib -Leaf)" | Out-Null
}

& adb @adbArgs shell "chmod 755 $remoteDir/il2cpp4k-inject" | Out-Null

if ($Action -eq 'list') {
    $command = "$remoteDir/il2cpp4k-inject list"
} else {
    $libName = Split-Path $Lib -Leaf
    $selector = if ($Target -match '^\d+$') { "--pid $Target" } else { "--name $Target" }
    $entryArgs = if ($CallEntry) { "--call-entry --entry $Entry" } else { '' }
    $command = "$remoteDir/il2cpp4k-inject inject --lib $remoteDir/$libName $selector $entryArgs"
}

Write-Host "running (root): $command"
& adb @adbArgs shell "su -c '$command'"
exit $LASTEXITCODE
