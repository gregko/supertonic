param(
    [string[]]$Abi = @("arm64-v8a", "x86_64"),
    [string]$AndroidSdkRoot = $(if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }),
    [string]$NdkVersion = "27.2.12479018",
    [string]$OnnxJniRoot = $(if ($env:ONNX_JNI_ROOT) { $env:ONNX_JNI_ROOT } elseif ($env:ONNX_ANDROID_ROOT) { Join-Path $env:ONNX_ANDROID_ROOT "jni" } else { Join-Path $HOME "onnxruntime-android\jni" }),
    [int]$MinSdk = 24,
    [switch]$SkipRustupTargetInstall
)

$ErrorActionPreference = "Stop"

function Resolve-Tool {
    param([string]$Name)

    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    $fallback = Join-Path $HOME ".cargo\bin\$Name.exe"
    if (Test-Path $fallback) {
        return $fallback
    }

    throw "$Name was not found. Install Rust and ensure cargo/rustup are available."
}

function Test-SharedLibraryAlignment {
    param(
        [string]$ReadElfPath,
        [string]$LibraryPath,
        [UInt32]$MinimumAlignment = 0x4000
    )

    if (-not (Test-Path $LibraryPath)) {
        throw "Shared library not found at $LibraryPath"
    }

    $loadLines = & $ReadElfPath -l $LibraryPath 2>$null | Select-String "LOAD"
    if (-not $loadLines) {
        throw "Unable to inspect ELF LOAD segments for $LibraryPath"
    }

    foreach ($line in $loadLines) {
        if ($line.Line -notmatch "(0x[0-9A-Fa-f]+)\s*$") {
            throw "Unable to parse ELF alignment from: $($line.Line)"
        }

        $alignmentHex = $Matches[1]
        $alignment = [Convert]::ToUInt32($alignmentHex, 16)
        if ($alignment -lt $MinimumAlignment) {
            return $false
        }
    }

    return $true
}

function Initialize-MsvcBuildEnvironment {
    if ((Get-Command cl -ErrorAction SilentlyContinue) -and (Get-Command link -ErrorAction SilentlyContinue)) {
        return
    }

    $vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
    if (-not (Test-Path $vswhere)) {
        throw "vswhere.exe was not found at $vswhere. Install Visual Studio with C++ build tools."
    }

    $vsInstallPath = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath 2>$null
    if (-not $vsInstallPath) {
        throw "Unable to locate a Visual Studio installation with the MSVC x64/x86 toolchain."
    }

    $devShellModule = Join-Path $vsInstallPath "Common7\Tools\Microsoft.VisualStudio.DevShell.dll"
    if (-not (Test-Path $devShellModule)) {
        throw "Visual Studio DevShell module was not found at $devShellModule"
    }

    Import-Module $devShellModule -ErrorAction Stop
    Enter-VsDevShell -VsInstallPath $vsInstallPath -DevCmdArguments "-arch=x64 -host_arch=x64" | Out-Null

    if (-not ((Get-Command cl -ErrorAction SilentlyContinue) -and (Get-Command link -ErrorAction SilentlyContinue))) {
        throw "MSVC tools are still unavailable after entering the Visual Studio developer shell."
    }
}

function Get-NdkToolchainBin {
    param([string]$NdkRoot)

    $prebuiltRoot = Join-Path $NdkRoot "toolchains\llvm\prebuilt"
    if (-not (Test-Path $prebuiltRoot)) {
        return $null
    }

    foreach ($hostDir in @("windows-x86_64", "windows")) {
        $candidate = Join-Path $prebuiltRoot "$hostDir\bin"
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    $fallback = Get-ChildItem $prebuiltRoot -Directory -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -First 1
    if ($fallback) {
        $candidate = Join-Path $fallback.FullName "bin"
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    return $null
}

function Resolve-NdkRoot {
    param(
        [string]$SdkRoot,
        [string]$PreferredVersion
    )

    $preferred = Join-Path $SdkRoot "ndk\$PreferredVersion"
    if ((Test-Path $preferred) -and (Get-NdkToolchainBin -NdkRoot $preferred)) {
        return $preferred
    }

    $ndkRoot = Join-Path $SdkRoot "ndk"
    $installed = Get-ChildItem $ndkRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { Get-NdkToolchainBin -NdkRoot $_.FullName } |
        Sort-Object Name
    if (-not $installed) {
        throw "No Android NDK installation with an LLVM toolchain was found under $ndkRoot"
    }

    $selected = $installed[-1]
    Write-Warning "NDK $PreferredVersion was not found. Using $($selected.Name) instead."
    return $selected.FullName
}

$abiMap = @{
    "arm64-v8a" = @{
        Target = "aarch64-linux-android"
        Linker = "aarch64-linux-android{0}-clang.cmd"
    }
    "x86_64" = @{
        Target = "x86_64-linux-android"
        Linker = "x86_64-linux-android{0}-clang.cmd"
    }
}

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Initialize-MsvcBuildEnvironment
$cargo = Resolve-Tool "cargo"
$rustup = $null

if (-not $SkipRustupTargetInstall) {
    try {
        $rustup = Resolve-Tool "rustup"
    } catch {
        Write-Warning "rustup was not found. Continuing without automatic target installation."
    }
}

$ndkRoot = Resolve-NdkRoot -SdkRoot $AndroidSdkRoot -PreferredVersion $NdkVersion
$toolchainBin = Get-NdkToolchainBin -NdkRoot $ndkRoot
if (-not (Test-Path $toolchainBin)) {
    throw "NDK toolchain bin directory was not found at $toolchainBin"
}

$readElf = Join-Path $toolchainBin "llvm-readelf.exe"
if (-not (Test-Path $readElf)) {
    throw "llvm-readelf.exe was not found at $readElf"
}

$requestedAbis = foreach ($rawAbi in $Abi) {
    foreach ($abiPart in ($rawAbi -split ',')) {
        $normalizedAbi = $abiPart.Trim()
        if ($normalizedAbi) {
            $normalizedAbi
        }
    }
}

if (-not $requestedAbis) {
    throw "No ABIs were provided."
}

Write-Host "=== Supertonic JNI Build Script (PowerShell) ==="
Write-Host "Requested ABIs: $($requestedAbis -join ', ')"
Write-Host "Android SDK: $AndroidSdkRoot"
Write-Host "NDK: $ndkRoot"
Write-Host "ONNX Runtime JNI root: $OnnxJniRoot"

foreach ($normalizedAbi in $requestedAbis) {
    if (-not $abiMap.ContainsKey($normalizedAbi)) {
        throw "Unsupported ABI '$normalizedAbi'. Supported values: arm64-v8a, x86_64"
    }

    $target = $abiMap[$normalizedAbi].Target
    $linkerName = $abiMap[$normalizedAbi].Linker -f $MinSdk
    $linkerPath = Join-Path $toolchainBin $linkerName
    $onnxAbiDir = Join-Path $OnnxJniRoot $normalizedAbi
    $onnxLib = Join-Path $onnxAbiDir "libonnxruntime.so"
    $jniLibsDir = Join-Path $repoRoot "android\app\src\main\jniLibs\$normalizedAbi"
    $builtRustLib = Join-Path $repoRoot "rust\target\$target\release\libsupertonic_tts.so"

    if (-not (Test-Path $linkerPath)) {
        throw "Linker not found at $linkerPath"
    }

    if (-not (Test-Path $onnxLib)) {
        throw "libonnxruntime.so was not found for ABI '$normalizedAbi' at $onnxLib"
    }

    if (-not (Test-SharedLibraryAlignment -ReadElfPath $readElf -LibraryPath $onnxLib)) {
        throw "libonnxruntime.so for ABI '$normalizedAbi' is not 16 KB aligned. Use onnxruntime-android 1.20.0 or newer."
    }

    if ($rustup) {
        & $rustup target add $target
    }

    $linkerEnvName = "CARGO_TARGET_{0}_LINKER" -f $target.ToUpperInvariant().Replace("-", "_")
    Set-Item -Path "Env:$linkerEnvName" -Value $linkerPath
    $env:ORT_STRATEGY = "system"
    $env:ORT_LIB_LOCATION = $onnxAbiDir

    Write-Host "[build] ABI=$normalizedAbi target=$target"
    Write-Host "        Using ONNX Runtime from: $onnxAbiDir"

    Push-Location (Join-Path $repoRoot "rust")
    try {
        & $cargo build --release --target $target
        if ($LASTEXITCODE -ne 0) {
            throw "cargo build failed for target $target"
        }
    } finally {
        Pop-Location
    }

    if (-not (Test-SharedLibraryAlignment -ReadElfPath $readElf -LibraryPath $builtRustLib)) {
        throw "Built Rust library for ABI '$normalizedAbi' is not 16 KB aligned."
    }

    New-Item -ItemType Directory -Force -Path $jniLibsDir | Out-Null
    Copy-Item $builtRustLib $jniLibsDir -Force
    Copy-Item $onnxLib $jniLibsDir -Force

    Write-Host "        Copied:"
    Get-ChildItem $jniLibsDir | Select-Object Name, Length
}

Write-Host "JNI libraries are ready under android\app\src\main\jniLibs"
