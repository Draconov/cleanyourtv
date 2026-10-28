param(
    [Parameter(Mandatory=$true, Position=0)]
    [string[]]$Images,
    [Parameter(Mandatory=$true)][string]$Device,
    [string]$Adb = ''
)
$ErrorActionPreference = 'Stop'
if (-not $Adb) {
    $candidate = Join-Path $env:USERPROFILE 'Downloads\platform-tools\adb.exe'
    if (Test-Path -LiteralPath $candidate) { $Adb = $candidate }
    else { $Adb = 'adb' }
}
$files = @()
foreach ($item in $Images) {
    $resolved = Resolve-Path -LiteralPath $item -ErrorAction Stop
    foreach ($path in $resolved) {
        $file = Get-Item -LiteralPath $path.Path
        if ($file.PSIsContainer) { throw "Expected an image file, not a folder: $file" }
        if ($file.Extension.ToLowerInvariant() -notin @('.jpg','.jpeg','.png','.webp')) {
            throw "Only JPG, PNG and WebP are supported: $file"
        }
        if ($file.Length -gt 25MB) { throw "Please use an image smaller than 25 MB: $file" }
        $files += $file
    }
}
if ($files.Count -lt 1 -or $files.Count -gt 8) { throw 'Select 1 to 8 image files.' }
Write-Host "Connecting to $Device..."
& $Adb connect $Device | Out-Host
& $Adb -s $Device get-state | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'ADB device is not connected. Confirm the current TV IP and port.' }
$folder = '/sdcard/Android/data/com.gathin.tvhome/files/Pictures/ROWA-Import'
& $Adb -s $Device shell mkdir -p $folder | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Cannot create the app-specific staging directory on this TV.' }
# Use predictable numbered names; never embed user-provided path content in adb shell commands.
$i = 1
foreach ($file in $files) {
    $name = ('wallpaper_{0:D2}{1}' -f $i, $file.Extension.ToLowerInvariant())
    $remote = "$folder/$name"
    Write-Host "Transferring $($file.Name)..."
    & $Adb -s $Device push $file.FullName $remote | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "ADB transfer failed: $($file.Name)" }
    $i++
}
Write-Host ''
Write-Host 'Files transferred. On the TV open:' -ForegroundColor Green
Write-Host '  Launcher Appearance -> Add background image(s)'
Write-Host 'The launcher will import the transferred images automatically.'
Write-Host 'Do not uninstall the launcher: its internal background images are app data.'
