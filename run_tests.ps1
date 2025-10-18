# =========================
# Paxos Council Election - Test Runner (PowerShell, fixed v2)
# =========================
$ErrorActionPreference = "Stop"
$root = Get-Location
$src  = Join-Path $root "src"
$logs = Join-Path $root "logs"

function Kill-CouncilMembers {
    Get-CimInstance Win32_Process -Filter "name='java.exe'" `
    | Where-Object { $_.CommandLine -match 'CouncilMember' } `
    | ForEach-Object { try { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue } catch {} }
}

function Safe-Remove([string]$path) {
    if (-not (Test-Path $path)) { return }
    for ($i=0; $i -lt 8; $i++) {
        try { Remove-Item $path -Recurse -Force -ErrorAction Stop; return }
        catch { Start-Sleep -Milliseconds 600 }
    }
    $ts = Get-Date -Format "yyyyMMdd-HHmmss"
    Rename-Item $path "${path}-stale-$ts" -ErrorAction SilentlyContinue
}

function Ensure-Logs-Clean {
    Kill-CouncilMembers
    Start-Sleep -Milliseconds 800
    Safe-Remove $logs
    New-Item $logs -ItemType Directory -Force | Out-Null
}

# 启动成员；可选：一开始就带 --propose
function Launch-Member {
    param(
        [string]$Id,
        [string]$Profile,
        [string]$ProposeValue = $null,
        [int]$ProposeDelay = 300
    )
    $args = "$Id --profile $Profile"
    if ($ProposeValue) { $args += " --propose $ProposeValue --propose-delay $ProposeDelay" }
    $logFile = Join-Path $logs "$Id.log"
    $cmd = "java -cp `"$src`" CouncilMember $args > `"$logFile`" 2>&1"
    Start-Process -FilePath "cmd.exe" -ArgumentList "/c", $cmd -PassThru -WindowStyle Hidden | Out-Null
}

function Scenario-CopyLogs($dstName) {
    $dst = Join-Path $root $dstName
    Safe-Remove $dst
    Copy-Item $logs $dst -Recurse
}

# 0) 编译
Write-Host "Compiling..."
javac "$src\CouncilMember.java"

# 1) S1: Ideal Network —— 在启动 M4 时就附带 --propose
Write-Host "`n=== Scenario 1: Ideal Network ==="
Ensure-Logs-Clean
for ($i=1; $i -le 9; $i++) {
    if ($i -eq 4) {
        Launch-Member "M$i" "reliable" "M5" 200
    } else {
        Launch-Member "M$i" "reliable"
    }
}
Start-Sleep -Seconds 6
Scenario-CopyLogs "logs-s1"
Kill-CouncilMembers

# 2) S2: Concurrent Proposals —— M1 和 M8 同时带 --propose
Write-Host "`n=== Scenario 2: Concurrent Proposals ==="
Ensure-Logs-Clean
for ($i=1; $i -le 9; $i++) {
    if ($i -eq 1) {
        Launch-Member "M$i" "reliable" "M1" 150
    } elseif ($i -eq 8) {
        Launch-Member "M$i" "reliable" "M8" 160
    } else {
        Launch-Member "M$i" "reliable"
    }
}
Start-Sleep -Seconds 7
Scenario-CopyLogs "logs-s2"
Kill-CouncilMembers

# 3) S3: Fault Tolerance —— M4/M2/M3 各自在其进程中触发提案
Write-Host "`n=== Scenario 3: Fault Tolerance ==="
Ensure-Logs-Clean
for ($i=1; $i -le 9; $i++) {
    switch ($i) {
        1 { Launch-Member "M1" "reliable" }
        2 { Launch-Member "M2" "latent"   "M6" 800 }   # 3b latent
        3 { Launch-Member "M3" "failing"  "M3" 300 }   # 3c failing
        4 { Launch-Member "M4" "standard" "M5" 200 }   # 3a standard
        default { Launch-Member "M$i" "standard" }
    }
}
Start-Sleep -Seconds 9
Scenario-CopyLogs "logs-s3"
Kill-CouncilMembers

# 4) Summary
Write-Host "`n=== Summary (CONSENSUS lines) ==="
Write-Host "S1:"; Get-ChildItem "logs-s1" -File | % { Select-String -Path $_.FullName -Pattern "CONSENSUS:" -SimpleMatch -ErrorAction SilentlyContinue }
Write-Host "`nS2:"; Get-ChildItem "logs-s2" -File | % { Select-String -Path $_.FullName -Pattern "CONSENSUS:" -SimpleMatch -ErrorAction SilentlyContinue }
Write-Host "`nS3:"; Get-ChildItem "logs-s3" -File | % { Select-String -Path $_.FullName -Pattern "CONSENSUS:" -SimpleMatch -ErrorAction SilentlyContinue }
Write-Host "`nDone. Logs -> logs-s1/, logs-s2/, logs-s3/"
