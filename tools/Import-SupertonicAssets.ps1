param(
    [Parameter(Mandatory = $true)][string]$SourceRoot,
    [string]$AssetSource = "",
    [switch]$UpdateLock
)

. (Join-Path $PSScriptRoot "Supertonic.AndroidUpdate.Common.ps1")

$repoRoot = Get-SupertonicRepoRoot
$assetLayout = Resolve-AssetSourceLayout -SourceRoot $SourceRoot
$targetOnnx = Join-Path $repoRoot "assets\onnx"
$targetVoiceStyles = Join-Path $repoRoot "assets\voice_styles"

$resolvedSourceOnnx = (Resolve-Path $assetLayout.onnx).Path
$resolvedTargetOnnx = $targetOnnx
if (Test-Path $targetOnnx) {
    $resolvedTargetOnnx = (Resolve-Path $targetOnnx).Path
}

if ($resolvedSourceOnnx -eq $resolvedTargetOnnx) {
    throw "Source and destination assets are the same directory: $resolvedSourceOnnx"
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backupRoot = Join-Path $repoRoot "tmp\asset-backups\$timestamp"

$onnxBackup = Backup-Directory -SourcePath $targetOnnx -BackupRoot $backupRoot -Label "onnx"
$voiceBackup = Backup-Directory -SourcePath $targetVoiceStyles -BackupRoot $backupRoot -Label "voice_styles"

Mirror-Directory -SourcePath $assetLayout.onnx -DestinationPath $targetOnnx
Mirror-Directory -SourcePath $assetLayout.voiceStyles -DestinationPath $targetVoiceStyles

Write-Host "Imported model assets from:"
Write-Host "  $SourceRoot"
if ($onnxBackup -or $voiceBackup) {
    Write-Host "Backup written to:"
    Write-Host "  $backupRoot"
}

if ($UpdateLock) {
    $assetSourceLabel = $AssetSource
    if (-not $assetSourceLabel) {
        $assetSourceLabel = $SourceRoot
    }

    & (Join-Path $PSScriptRoot "Update-AndroidInputsLock.ps1") -AssetSource $assetSourceLabel
} else {
    Write-Host "Next step: evaluate the imported assets with"
    Write-Host "  .\tools\Build-SupertonicAndroid.ps1 -SkipLockCheck"
}
