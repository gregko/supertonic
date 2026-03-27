param(
    [string]$OnnxJniRoot = (Join-Path $HOME "onnxruntime-android\jni"),
    [switch]$AsJson
)

. (Join-Path $PSScriptRoot "Supertonic.AndroidUpdate.Common.ps1")

$repoRoot = Get-SupertonicRepoRoot
$lockPath = Get-AndroidInputsLockPath
$lock = Read-JsonFile -Path $lockPath
$issues = @()

if ($lock) {
    $issues = @(Compare-AndroidInputsToLock -Lock $lock -OnnxJniRoot $OnnxJniRoot)
}

$apkPath = Join-Path $repoRoot "android\app\build\outputs\apk\debug\app-debug.apk"
$status = [ordered]@{
    repoRoot        = $repoRoot
    repo            = Get-GitState
    lockPath        = $lockPath
    lockPresent     = [bool](Test-Path $lockPath)
    lockMatchesDisk = ($null -ne $lock -and $issues.Count -eq 0)
    onnxJniRoot     = $OnnxJniRoot
    apkPresent      = [bool](Test-Path $apkPath)
    apkPath         = $apkPath
    issues          = $issues
}

if ($AsJson) {
    $status | ConvertTo-Json -Depth 10
    exit 0
}

Write-Host "Repo: $($status.repoRoot)"
Write-Host "Branch: $($status.repo.branch)"
Write-Host "Commit: $($status.repo.commit)"
Write-Host "Dirty: $($status.repo.dirty)"
Write-Host "Lock file: $($status.lockPath)"
Write-Host "Lock present: $($status.lockPresent)"
Write-Host "ONNX Runtime JNI root: $($status.onnxJniRoot)"
Write-Host "APK present: $($status.apkPresent)"

if (-not $lock) {
    Write-Warning "No lock file is present yet. Run .\tools\Update-AndroidInputsLock.ps1 after accepting the current inputs."
    exit 0
}

if ($issues.Count -eq 0) {
    Write-Host "Status: current local inputs match the committed lock file."
    exit 0
}

Write-Warning "Status: current local inputs differ from the committed lock file."
foreach ($issue in $issues) {
    Write-Host " - $issue"
}
