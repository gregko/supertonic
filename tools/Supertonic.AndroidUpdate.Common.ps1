Set-StrictMode -Version Latest

$script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$script:LockPath = Join-Path $PSScriptRoot "android-inputs.lock.json"
$script:DefaultOnnxInstallRoot = Join-Path $HOME "onnxruntime-android"
$script:DefaultOnnxJniRoot = Join-Path $script:DefaultOnnxInstallRoot "jni"
$script:DefaultOnnxRuntimeVersion = "1.20.0"
$script:DefaultAbiFilters = @("arm64-v8a", "x86_64")
$script:DefaultNdkVersion = "27.2.12479018"
$script:TrackedAssetFiles = @(
    "assets/onnx/duration_predictor.onnx",
    "assets/onnx/text_encoder.onnx",
    "assets/onnx/tts.json",
    "assets/onnx/unicode_indexer.json",
    "assets/onnx/vector_estimator.onnx",
    "assets/onnx/vocoder.onnx",
    "assets/voice_styles/M1.json",
    "assets/voice_styles/M2.json",
    "assets/voice_styles/M3.json",
    "assets/voice_styles/M4.json",
    "assets/voice_styles/M5.json",
    "assets/voice_styles/F1.json",
    "assets/voice_styles/F2.json",
    "assets/voice_styles/F3.json",
    "assets/voice_styles/F4.json",
    "assets/voice_styles/F5.json"
)

function Get-SupertonicRepoRoot {
    return $script:RepoRoot
}

function Get-AndroidInputsLockPath {
    return $script:LockPath
}

function Get-DefaultOnnxInstallRoot {
    return $script:DefaultOnnxInstallRoot
}

function Get-DefaultOnnxJniRoot {
    return $script:DefaultOnnxJniRoot
}

function Get-DefaultOnnxRuntimeVersion {
    return $script:DefaultOnnxRuntimeVersion
}

function Get-DefaultAbiFilters {
    return ,$script:DefaultAbiFilters
}

function Get-DefaultNdkVersion {
    return $script:DefaultNdkVersion
}

function Get-TrackedAssetFiles {
    return ,$script:TrackedAssetFiles
}

function Get-AndroidSdkRoot {
    if ($env:ANDROID_SDK_ROOT) {
        return $env:ANDROID_SDK_ROOT
    }

    if ($env:ANDROID_HOME) {
        return $env:ANDROID_HOME
    }

    return (Join-Path $env:LOCALAPPDATA "Android\Sdk")
}

function Read-JsonFile {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path $Path)) {
        return $null
    }

    return (Get-Content $Path -Raw | ConvertFrom-Json -Depth 10)
}

function Write-JsonFile {
    param(
        [Parameter(Mandatory = $true)]$Object,
        [Parameter(Mandatory = $true)][string]$Path
    )

    $json = $Object | ConvertTo-Json -Depth 10
    Set-Content -Path $Path -Value ($json + [Environment]::NewLine)
}

function Get-GitState {
    $state = [ordered]@{
        branch = $null
        commit = $null
        dirty  = $null
    }

    $git = Get-Command git -ErrorAction SilentlyContinue
    if (-not $git) {
        return $state
    }

    try {
        $state.branch = (& git -C $script:RepoRoot rev-parse --abbrev-ref HEAD 2>$null).Trim()
    } catch {
    }

    try {
        $state.commit = (& git -C $script:RepoRoot rev-parse HEAD 2>$null).Trim()
    } catch {
    }

    try {
        $dirtyOutput = & git -C $script:RepoRoot status --porcelain 2>$null
        $state.dirty = [bool]($dirtyOutput | Select-Object -First 1)
    } catch {
    }

    return $state
}

function Normalize-RelativePath {
    param([Parameter(Mandatory = $true)][string]$Path)
    return $Path.Replace('\', '/')
}

function New-TrackedFileRecord {
    param(
        [Parameter(Mandatory = $true)][string]$FullPath,
        [Parameter(Mandatory = $true)][string]$RelativePath
    )

    if (-not (Test-Path $FullPath)) {
        throw "Tracked file not found: $FullPath"
    }

    $item = Get-Item $FullPath
    return [ordered]@{
        path   = Normalize-RelativePath $RelativePath
        size   = [int64]$item.Length
        sha256 = (Get-FileHash -Algorithm SHA256 $FullPath).Hash
    }
}

function New-OnnxRuntimeFileRecord {
    param(
        [Parameter(Mandatory = $true)][string]$FullPath,
        [Parameter(Mandatory = $true)][string]$RelativePath,
        [Parameter(Mandatory = $true)][string]$Abi
    )

    if (-not (Test-Path $FullPath)) {
        throw "ONNX Runtime library not found: $FullPath"
    }

    $item = Get-Item $FullPath
    return [ordered]@{
        abi    = $Abi
        path   = Normalize-RelativePath $RelativePath
        size   = [int64]$item.Length
        sha256 = (Get-FileHash -Algorithm SHA256 $FullPath).Hash
    }
}

function Normalize-AbiList {
    param([string[]]$AbiList)

    $normalized = @()
    foreach ($abiValue in $AbiList) {
        foreach ($part in ($abiValue -split ',')) {
            $trimmed = $part.Trim()
            if ($trimmed) {
                $normalized += $trimmed
            }
        }
    }

    return $normalized
}

function New-AndroidInputsLock {
    param(
        [string]$OnnxRuntimeVersion = $script:DefaultOnnxRuntimeVersion,
        [string]$OnnxJniRoot = $script:DefaultOnnxJniRoot,
        [string]$AssetSource = "huggingface.co/Supertone/supertonic-2",
        [string]$OnnxRuntimeSource = "maven:com.microsoft.onnxruntime:onnxruntime-android"
    )

    $assetFiles = foreach ($relativePath in $script:TrackedAssetFiles) {
        New-TrackedFileRecord -FullPath (Join-Path $script:RepoRoot $relativePath) -RelativePath $relativePath
    }

    $onnxInstallRoot = Split-Path $OnnxJniRoot -Parent
    $onnxFiles = foreach ($abi in $script:DefaultAbiFilters) {
        $relativePath = "jni/$abi/libonnxruntime.so"
        New-OnnxRuntimeFileRecord -FullPath (Join-Path $onnxInstallRoot ($relativePath -replace '/', '\')) -RelativePath $relativePath -Abi $abi
    }

    return [ordered]@{
        schemaVersion = 1
        generatedAt   = (Get-Date).ToString("o")
        repo          = Get-GitState
        modelAssets   = [ordered]@{
            source       = $AssetSource
            layout       = "assets/onnx + assets/voice_styles"
            modelVersion = "v2"
            files        = $assetFiles
        }
        onnxRuntimeAndroid = [ordered]@{
            version     = $OnnxRuntimeVersion
            source      = $OnnxRuntimeSource
            installRoot = $onnxInstallRoot
            files       = $onnxFiles
        }
        rust = [ordered]@{
            ortCrateVersion = "=2.0.0-rc.7"
            vendorPath      = "rust/vendor/ort-2.0.0-rc.7"
        }
        androidBuild = [ordered]@{
            abis      = $script:DefaultAbiFilters
            ndkVersion = $script:DefaultNdkVersion
            apkPath   = "android/app/build/outputs/apk/debug/app-debug.apk"
        }
    }
}

function Compare-AndroidInputsToLock {
    param(
        [Parameter(Mandatory = $true)]$Lock,
        [string]$OnnxJniRoot = $script:DefaultOnnxJniRoot
    )

    $issues = New-Object System.Collections.Generic.List[string]

    foreach ($file in @($Lock.modelAssets.files)) {
        $fullPath = Join-Path $script:RepoRoot ($file.path -replace '/', '\')
        if (-not (Test-Path $fullPath)) {
            $issues.Add("Missing asset file: $($file.path)")
            continue
        }

        $actualHash = (Get-FileHash -Algorithm SHA256 $fullPath).Hash
        if ($actualHash -ne $file.sha256) {
            $issues.Add("Asset hash mismatch: $($file.path)")
        }
    }

    $onnxInstallRoot = Split-Path $OnnxJniRoot -Parent
    foreach ($file in @($Lock.onnxRuntimeAndroid.files)) {
        $fullPath = Join-Path $onnxInstallRoot ($file.path -replace '/', '\')
        if (-not (Test-Path $fullPath)) {
            $issues.Add("Missing ONNX Runtime library: $($file.path)")
            continue
        }

        $actualHash = (Get-FileHash -Algorithm SHA256 $fullPath).Hash
        if ($actualHash -ne $file.sha256) {
            $issues.Add("ONNX Runtime hash mismatch: $($file.path)")
        }
    }

    return $issues.ToArray()
}

function Get-BuildToolsZipalignPath {
    param([string]$AndroidSdkRoot = (Get-AndroidSdkRoot))

    $buildToolsRoot = Join-Path $AndroidSdkRoot "build-tools"
    if (-not (Test-Path $buildToolsRoot)) {
        return $null
    }

    $directories = Get-ChildItem $buildToolsRoot -Directory | Sort-Object `
        @{ Expression = { if ($_.Name -match '^\d+(\.\d+)+$') { 1 } else { 0 } }; Descending = $true },
        @{ Expression = { [version](($_.Name -replace '-.*$', '')) }; Descending = $true },
        @{ Expression = { $_.Name }; Descending = $true }

    foreach ($directory in $directories) {
        $candidate = Join-Path $directory.FullName "zipalign.exe"
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    return $null
}

function Get-ApkEntryNames {
    param([Parameter(Mandatory = $true)][string]$ApkPath)

    if (-not (Test-Path $ApkPath)) {
        throw "APK not found at $ApkPath"
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($ApkPath)
    try {
        return @($archive.Entries | ForEach-Object { $_.FullName })
    } finally {
        $archive.Dispose()
    }
}

function Backup-Directory {
    param(
        [Parameter(Mandatory = $true)][string]$SourcePath,
        [Parameter(Mandatory = $true)][string]$BackupRoot,
        [Parameter(Mandatory = $true)][string]$Label
    )

    if (-not (Test-Path $SourcePath)) {
        return $null
    }

    $destination = Join-Path $BackupRoot $Label
    New-Item -ItemType Directory -Force -Path (Split-Path $destination -Parent) | Out-Null
    Copy-Item $SourcePath -Destination $destination -Recurse -Force
    return $destination
}

function Mirror-Directory {
    param(
        [Parameter(Mandatory = $true)][string]$SourcePath,
        [Parameter(Mandatory = $true)][string]$DestinationPath
    )

    if (-not (Test-Path $SourcePath)) {
        throw "Source directory not found: $SourcePath"
    }

    if (Test-Path $DestinationPath) {
        $resolvedDestination = (Resolve-Path $DestinationPath).Path
        if ($resolvedDestination.TrimEnd('\').Length -le 3) {
            throw "Refusing to delete unsafe destination path: $resolvedDestination"
        }
        Remove-Item $resolvedDestination -Recurse -Force
    }

    New-Item -ItemType Directory -Force -Path (Split-Path $DestinationPath -Parent) | Out-Null
    Copy-Item $SourcePath -Destination $DestinationPath -Recurse -Force
}

function Resolve-AssetSourceLayout {
    param([Parameter(Mandatory = $true)][string]$SourceRoot)

    $candidates = @(
        [ordered]@{
            onnx        = Join-Path $SourceRoot "onnx"
            voiceStyles = Join-Path $SourceRoot "voice_styles"
        },
        [ordered]@{
            onnx        = Join-Path $SourceRoot "assets\onnx"
            voiceStyles = Join-Path $SourceRoot "assets\voice_styles"
        },
        [ordered]@{
            onnx        = Join-Path $SourceRoot "V2\supertonic-2\onnx"
            voiceStyles = Join-Path $SourceRoot "V2\supertonic-2\voice_styles"
        }
    )

    foreach ($candidate in $candidates) {
        if ((Test-Path $candidate.onnx) -and (Test-Path $candidate.voiceStyles)) {
            return $candidate
        }
    }

    throw "Could not find a compatible asset layout under $SourceRoot"
}
