param(
    [Parameter(Mandatory=$true)][string]$Folder,
    [Parameter(Mandatory=$true)][string]$Device,
    [string]$Adb = ''
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Folder -PathType Container)) { throw "Not a folder: $Folder" }
if (-not $Adb) {
    $candidate = Join-Path $env:USERPROFILE 'Downloads\platform-tools\adb.exe'
    $Adb = if (Test-Path -LiteralPath $candidate) { $candidate } else { 'adb' }
}
$dir = (Resolve-Path -LiteralPath $Folder).Path
Write-Host "Copying $dir to the TV's public Download directory."
& $Adb connect $Device | Out-Host
& $Adb -s $Device get-state | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'ADB device not connected. Check the current TV IP.' }
& $Adb -s $Device push "$dir" '/sdcard/Download/' | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Transfer failed; no files were imported into the launcher.' }
Write-Host 'Copied. Open Launcher Appearance -> Choose background images. Set source folder inside the gallery if needed.' -ForegroundColor Green
Write-Host 'If images are missing, they may not yet be indexed by Android MediaStore. X-plore can confirm the copied files exist.'
