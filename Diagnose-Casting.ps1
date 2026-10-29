# Read-only Android TV Cast / mirroring diagnostic.
# Does not change packages, permissions, settings, processes or network configuration.
param(
    [Parameter(Mandatory = $true)][string]$Device,
    [string]$AdbPath = ''
)

$ErrorActionPreference = 'Stop'

if (-not $AdbPath) {
    $candidate = Join-Path $env:USERPROFILE 'Downloads\platform-tools\adb.exe'
    if (Test-Path -LiteralPath $candidate) {
        $AdbPath = $candidate
    } else {
        $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
        if (-not $adbCommand) {
            throw 'ADB not found. Specify -AdbPath C:\path\to\adb.exe'
        }
        $AdbPath = $adbCommand.Source
    }
}
if (-not (Test-Path -LiteralPath $AdbPath)) {
    throw "ADB not found at: $AdbPath"
}

# Diagnostic ADB shell commands sometimes exit 1 when the requested process is
# not running. Treat this as report data, not a script-ending PowerShell error.
function Invoke-CastShell {
    param([Parameter(Mandatory = $true)][string]$Command)

    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $AdbPath -s $Device shell $Command 2>&1)
        $commandExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }

    $result = New-Object 'System.Collections.Generic.List[string]'
    foreach ($entry in $output) {
        if ($entry -is [System.Management.Automation.ErrorRecord]) {
            [void]$result.Add($entry.Exception.Message)
        } else {
            [void]$result.Add([string]$entry)
        }
    }
    if ($commandExitCode -ne 0) {
        [void]$result.Add("[ADB command exit code: $commandExitCode; a missing process may be normal]")
    }
    return $result.ToArray()
}

# Connect and verify the TV before capturing diagnostics.
& $AdbPath connect $Device | Out-Host
$state = (& $AdbPath -s $Device get-state 2>&1 | Out-String).Trim()
if ($state -ne 'device') { throw "TV is not connected/authorized: $state" }

$report = Join-Path $env:TEMP ('ROWA-casting-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.txt')
$lines = New-Object 'System.Collections.Generic.List[string]'

function Add-CastSection {
    param([Parameter(Mandatory = $true)][string]$Title)
    [void]$lines.Add('')
    [void]$lines.Add('==== ' + $Title + ' ====')
}
function Add-CastCommand {
    param([Parameter(Mandatory = $true)][string]$Command)
    [void]$lines.Add('$ adb shell ' + $Command)
    foreach ($line in @(Invoke-CastShell -Command $Command)) {
        [void]$lines.Add([string]$line)
    }
}

[void]$lines.Add('ROWA casting diagnostics (read only). Device: ' + $Device)
[void]$lines.Add('Captured: ' + (Get-Date).ToString('s'))

Add-CastSection 'TV model / build'
Add-CastCommand 'getprop ro.product.model'
Add-CastCommand 'getprop ro.build.version.release'

Add-CastSection 'Disabled packages (most important)'
Add-CastCommand 'pm list packages -d'

Add-CastSection 'Cast, receiver, mirroring and related installed packages'
$packages = @(Invoke-CastShell -Command 'pm list packages')
$disabled = @(Invoke-CastShell -Command 'pm list packages -d')
foreach ($entry in $packages) {
    $package = ([string]$entry).Trim()
    if ($package -notmatch '^package:') { continue }
    if ($package -notmatch 'cast|chrome|media(shell)?|miracast|wfd|wireless|projection|gms|launcherx|tcl|coocaa|airplay') { continue }
    $isDisabled = $disabled -contains $package
    $prefix = if ($isDisabled) { '[DISABLED] ' } else { '[enabled/listed] ' }
    [void]$lines.Add($prefix + $package)
}

Add-CastSection 'Google Cast MediaShell package state'
$mediaShellState = @(Invoke-CastShell -Command 'dumpsys package com.google.android.apps.mediashell')
foreach ($line in $mediaShellState) {
    if ($line -match 'User 0:|enabled=|stopped=|installed=|Unable to find package|not found|exit code') {
        [void]$lines.Add([string]$line)
    }
}

Add-CastSection 'Receiver and stock launcher process IDs'
Add-CastCommand 'pidof com.google.android.apps.mediashell'
Add-CastCommand 'pidof com.google.android.gms'
Add-CastCommand 'pidof com.google.android.apps.tv.launcherx'

Add-CastSection 'TV network interfaces (check network connectivity)'
Add-CastCommand 'ip -4 addr show'

Add-CastSection 'Relevant running processes'
foreach ($entry in @(Invoke-CastShell -Command 'ps -A')) {
    if ($entry -match 'cast|media(shell)?|miracast|wfd|gms|launcherx|projection') {
        [void]$lines.Add([string]$entry)
    }
}

$lines | Set-Content -LiteralPath $report -Encoding UTF8
Write-Host "Saved report: $report"
Write-Host 'Read-only diagnostic finished. No TV apps or settings were changed.'
Write-Host 'Review the report for identifying details before uploading.'
Write-Host 'Google Cast and Miracast use different receivers; enabled package lists do not guarantee casting works.'
