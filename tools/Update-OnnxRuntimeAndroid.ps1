param(
    [string]$Version = "1.20.0",
    [string]$InstallRoot = (Join-Path $HOME "onnxruntime-android"),
    [switch]$UpdateLock
)

. (Join-Path $PSScriptRoot "Supertonic.AndroidUpdate.Common.ps1")

$repoRoot = Get-SupertonicRepoRoot
$downloadDir = Join-Path $repoRoot "tmp\downloads"
$stagingRoot = Join-Path $repoRoot "tmp\onnxruntime-android-$Version"
$aarPath = Join-Path $downloadDir "onnxruntime-android-$Version.aar"
$zipPath = Join-Path $downloadDir "onnxruntime-android-$Version.zip"
$downloadUrl = "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/$Version/onnxruntime-android-$Version.aar"

New-Item -ItemType Directory -Force -Path $downloadDir | Out-Null

Write-Host "Downloading ONNX Runtime Android $Version"
Write-Host "  $downloadUrl"
Invoke-WebRequest -Uri $downloadUrl -OutFile $aarPath

Copy-Item $aarPath $zipPath -Force
if (Test-Path $stagingRoot) {
    Remove-Item $stagingRoot -Recurse -Force
}

Expand-Archive -Path $zipPath -DestinationPath $stagingRoot -Force

foreach ($abi in Get-DefaultAbiFilters) {
    $requiredLib = Join-Path $stagingRoot "jni\$abi\libonnxruntime.so"
    if (-not (Test-Path $requiredLib)) {
        throw "Downloaded package is missing $requiredLib"
    }
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backupRoot = Join-Path $repoRoot "tmp\onnxruntime-backups\$timestamp"
$backup = Backup-Directory -SourcePath $InstallRoot -BackupRoot $backupRoot -Label "onnxruntime-android"

Mirror-Directory -SourcePath $stagingRoot -DestinationPath $InstallRoot

Write-Host "Installed ONNX Runtime Android $Version into:"
Write-Host "  $InstallRoot"
if ($backup) {
    Write-Host "Backup written to:"
    Write-Host "  $backupRoot"
}

if ($UpdateLock) {
    & (Join-Path $PSScriptRoot "Update-AndroidInputsLock.ps1") `
        -OnnxRuntimeVersion $Version `
        -OnnxJniRoot (Join-Path $InstallRoot "jni")
} else {
    Write-Host "Next step: evaluate the updated runtime with"
    Write-Host "  .\tools\Build-SupertonicAndroid.ps1 -SkipLockCheck"
}
