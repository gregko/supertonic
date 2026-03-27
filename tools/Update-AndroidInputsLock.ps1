param(
    [string]$OnnxRuntimeVersion = "1.20.0",
    [string]$OnnxJniRoot = (Join-Path $HOME "onnxruntime-android\jni"),
    [string]$AssetSource = "huggingface.co/Supertone/supertonic-2",
    [string]$OnnxRuntimeSource = "maven:com.microsoft.onnxruntime:onnxruntime-android"
)

. (Join-Path $PSScriptRoot "Supertonic.AndroidUpdate.Common.ps1")

$lock = New-AndroidInputsLock `
    -OnnxRuntimeVersion $OnnxRuntimeVersion `
    -OnnxJniRoot $OnnxJniRoot `
    -AssetSource $AssetSource `
    -OnnxRuntimeSource $OnnxRuntimeSource

$lockPath = Get-AndroidInputsLockPath
Write-JsonFile -Object $lock -Path $lockPath

Write-Host "Updated Android input lock file:"
Write-Host "  $lockPath"
