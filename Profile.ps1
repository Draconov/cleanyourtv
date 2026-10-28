param([string]$Adb='',
      [Parameter(Mandatory=$true)][string]$Tv)
$ErrorActionPreference='Stop'
if (-not $Adb) {
    $candidate = Join-Path $env:USERPROFILE 'Downloads\platform-tools\adb.exe'
    $Adb = if (Test-Path -LiteralPath $candidate) { $candidate } else { 'adb' }
}
if ($Adb -ne 'adb' -and -not (Test-Path -LiteralPath $Adb)) { throw "ADB not found: $Adb" }
if ($Adb -eq 'adb' -and -not (Get-Command adb -ErrorAction SilentlyContinue)) { throw 'ADB is not on PATH; pass -Adb <path to adb.exe>' }
& $Adb connect $Tv | Out-Null
& $Adb -s $Tv get-state | Out-Host
if($LASTEXITCODE -ne 0){throw 'TV is not reachable.'}
$dest=Join-Path $PSScriptRoot ('ROWA-performance-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.txt')
$results=New-Object System.Collections.Generic.List[string]
$results.Add('ROWA performance snapshot; read-only. No force-stop, app restart or cache clearing.')
$results.Add('Local capture: '+(Get-Date -Format o))
$results.Add('TV: '+$Tv)
foreach($item in @(
 @{Name='Memory (PSS / Java / native / graphic)';Args=@('shell','dumpsys','meminfo','com.gathin.tvhome')},
 @{Name='Frame statistics';Args=@('shell','dumpsys','gfxinfo','com.gathin.tvhome','framestats')},
 @{Name='Current process';Args=@('shell','pidof','com.gathin.tvhome')},
 @{Name='First-frame startup log (v15)';Args=@('logcat','-d','-s','ROWA_PERF_V15:I','*:S')}
)) {
 $results.Add("`n=== "+$item.Name+' ===')
 $adbArgs=@("-s",$Tv) + [string[]]$item.Args
 $output=& $Adb @adbArgs 2>&1
 $results.Add(($output | Out-String))
}
[IO.File]::WriteAllLines($dest,[string[]]$results.ToArray(),[System.Text.UTF8Encoding]::new($false))
Write-Host "Saved performance snapshot: $dest" -ForegroundColor Green
Write-Host 'Compare before/after captures while the launcher is visible in the same layout.'
