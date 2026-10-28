param(
    [switch]$Install,
    [string]$Device = '',
    [string]$Adb = ''
)
$ErrorActionPreference = 'Stop'
if ($Install -and [string]::IsNullOrWhiteSpace($Device)) { throw "Specify the TV: .\Build-Install.ps1 -Install -Device '192.168.1.50:5555'" }
$launcher = Join-Path $PSScriptRoot 'launcher'
$gradle = Join-Path $launcher 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradle)) { throw "Missing Gradle wrapper: $gradle" }

# The user's tested build toolchain uses JDK 17. Prefer it without modifying system-wide settings.
$javaCandidates = @(Get-ChildItem 'C:\Program Files\Eclipse Adoptium\jdk-17*' -Directory -ErrorAction SilentlyContinue)
if ($javaCandidates.Count -gt 0) {
    $env:JAVA_HOME = $javaCandidates[0].FullName
    $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
}
if (-not $env:ANDROID_HOME) {
    $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path -LiteralPath $sdk) { $env:ANDROID_HOME = $sdk }
}

$log = Join-Path $PSScriptRoot ('Build-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
Write-Host "Project: $launcher"
Write-Host "Building APK; log: $log"
Push-Location $launcher
try {
    # Gradle prints warnings and Java compiler errors on stderr. Keep them in a log,
    # and always use its real exit code rather than PowerShell error-stream heuristics.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $gradle --no-daemon assembleRelease 2>&1 | Tee-Object -FilePath $log
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
} finally { Pop-Location }
if ($code -ne 0) { throw "Build failed (exit $code). See $log. The TV was not changed." }

$apk = Join-Path $launcher 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path -LiteralPath $apk)) { throw "Build exited successfully but APK was not found: $apk" }
Write-Host "Build succeeded: $apk" -ForegroundColor Green
if (-not $Install) { Write-Host 'Build-only: TV remains untouched. To install, rerun with -Install.'; return }

if (-not $Adb) {
    $candidate = Join-Path $PSScriptRoot '..\platform-tools\adb.exe'
    if (Test-Path -LiteralPath $candidate) { $Adb = (Resolve-Path $candidate).Path }
    else {
        $candidate = Join-Path $env:USERPROFILE 'Downloads\platform-tools\adb.exe'
        if (Test-Path -LiteralPath $candidate) { $Adb = $candidate }
        else { $Adb = 'adb' }
    }
}
Write-Host "Connecting to TV $Device with ADB: $Adb"
& $Adb connect $Device | Out-Host
& $Adb -s $Device get-state | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'ADB device not ready; nothing installed.' }
& $Adb -s $Device install -r $apk | Out-Host
if ($LASTEXITCODE -ne 0) {
    throw 'ADB install failed. Do not uninstall the previously working launcher to bypass a signing-key mismatch.'
}
Write-Host 'APK installed. Check the launcher, Bluetooth shortcuts, Home and held volume on the TV.' -ForegroundColor Green
