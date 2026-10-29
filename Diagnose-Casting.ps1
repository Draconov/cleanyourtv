# Read-only Android TV cast and mirroring diagnostic. Does not change packages or settings.
param(
    [Parameter(Mandatory=$true)][string]$Device,
    [string]$AdbPath = ''
)
$ErrorActionPreference = 'Stop'
if (-not $AdbPath) {
    $candidate = Join-Path $env:USERPROFILE 'Downloads\platform-tools\adb.exe'
    if (Test-Path $candidate) { $AdbPath = $candidate }
    else {
        $command = Get-Command adb -ErrorAction SilentlyContinue
        if (-not $command) { throw 'ADB not found. Supply -AdbPath C:\path\to\adb.exe' }
        $AdbPath = $command.Source
    }
}
if (-not (Test-Path $AdbPath)) { throw "ADB not found at: $AdbPath" }
& $AdbPath connect $Device | Out-Host
$state = (& $AdbPath -s $Device get-state 2>&1 | Out-String).Trim()
if ($state -ne 'device') { throw "TV is not connected/authorized: $state" }
$report = Join-Path $env:TEMP ('ROWA-casting-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.txt')
$lines = New-Object 'System.Collections.Generic.List[string]'
function Section([string]$title) { $lines.Add(''); $lines.Add('==== ' + $title + ' ====') }
function Data([string]$command) {
    $output = & $AdbPath -s $Device shell $command 2>&1
    foreach ($line in @($output)) { $lines.Add([string]$line) }
}
$lines.Add('ROWA casting diagnostics (read only). Device: ' + $Device)
$lines.Add('Captured: ' + (Get-Date).ToString('s'))
Section 'TV model / build'
Data 'getprop ro.product.model'
Data 'getprop ro.build.version.release'
Section 'Disabled packages (these are most important)'
Data 'pm list packages -d'
Section 'Relevant installed/disabled cast and mirror packages'
$packages = @(& $AdbPath -s $Device shell pm list packages -u)
$disabled = @(& $AdbPath -s $Device shell pm list packages -d)
foreach ($entry in $packages | Where-Object { $_ -match 'cast|chrome|media(shell)?|miracast|wfd|wireless|projection|gms|launcherx|tcl|coocaa|airplay' }) {
    $package = ([string]$entry).Trim()
    $isDisabled = $disabled -contains $package
    $lines.Add($(if ($isDisabled) { '[DISABLED] ' } else { '[present]  ' }) + $package)
}
Section 'MediaShell state and processes'
Data 'dumpsys package com.google.android.apps.mediashell | grep -E "User 0:|enabled=|stopped=|installed="'
Data 'pidof com.google.android.apps.mediashell'
Data 'pidof com.google.android.gms'
Data 'pidof com.google.android.apps.tv.launcherx'
Section 'TV network addresses (verify same network as your phone)'
Data 'ip -4 addr show'
Section 'Cast/mirror related active processes'
$processes = @(& $AdbPath -s $Device shell ps -A)
foreach ($entry in $processes | Where-Object { $_ -match 'cast|media(shell)?|miracast|wfd|gms|launcherx|projection' }) {
    $lines.Add([string]$entry)
}
$lines | Set-Content -LiteralPath $report -Encoding UTF8
Write-Host "Saved report: $report"
Write-Host 'This script did NOT enable/disable anything.'
Write-Host 'Read [DISABLED] entries first. Google Cast and Miracast use different receivers.'
Write-Host 'Before sharing, review the report for identifying network/device details.'
