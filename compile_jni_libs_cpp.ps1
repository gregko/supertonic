param(
    [string[]]$Abi = @("arm64-v8a", "x86_64"),
    [string]$AndroidSdkRoot = $(if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }),
    [string]$NdkVersion = "27.2.12479018",
    [string]$OnnxJniRoot = $(if ($env:ONNX_JNI_ROOT) { $env:ONNX_JNI_ROOT } elseif ($env:ONNX_ANDROID_ROOT) { Join-Path $env:ONNX_ANDROID_ROOT "jni" } else { Join-Path $HOME "onnxruntime-android\jni" }),
    [int]$MinSdk = 24
)

$ErrorActionPreference = "Stop"

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

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ndkRoot = Resolve-NdkRoot -SdkRoot $AndroidSdkRoot -PreferredVersion $NdkVersion
$toolchainBin = Get-NdkToolchainBin -NdkRoot $ndkRoot

if (-not (Test-Path $toolchainBin)) {
    throw "NDK toolchain bin directory was not found at $toolchainBin"
}

$readElf = Join-Path $toolchainBin "llvm-readelf.exe"
if (-not (Test-Path $readElf)) {
    throw "llvm-readelf.exe was not found at $readElf"
}

$cmakeExe = Get-Command cmake -ErrorAction SilentlyContinue
if (-not $cmakeExe) {
    # Try Android SDK cmake
    $sdkCmakeDir = Join-Path $AndroidSdkRoot "cmake"
    if (Test-Path $sdkCmakeDir) {
        $latest = Get-ChildItem $sdkCmakeDir -Directory | Sort-Object Name | Select-Object -Last 1
        if ($latest) {
            $candidate = Join-Path $latest.FullName "bin\cmake.exe"
            if (Test-Path $candidate) {
                $cmakeExe = $candidate
            }
        }
    }
    if (-not $cmakeExe) {
        throw "cmake was not found. Install CMake or the Android SDK CMake component."
    }
} else {
    $cmakeExe = $cmakeExe.Source
}

$ndkCmakeToolchain = Join-Path $ndkRoot "build\cmake\android.toolchain.cmake"
if (-not (Test-Path $ndkCmakeToolchain)) {
    throw "Android NDK CMake toolchain not found at $ndkCmakeToolchain"
}

# Locate Ninja - check system PATH first, then Android SDK cmake dirs
$ninjaExe = Get-Command ninja -ErrorAction SilentlyContinue
if ($ninjaExe) {
    $ninjaExe = $ninjaExe.Source
} else {
    $sdkCmakeDir = Join-Path $AndroidSdkRoot "cmake"
    if (Test-Path $sdkCmakeDir) {
        $ninjaCandidates = Get-ChildItem $sdkCmakeDir -Directory | Sort-Object Name -Descending
        foreach ($dir in $ninjaCandidates) {
            $candidate = Join-Path $dir.FullName "bin\ninja.exe"
            if (Test-Path $candidate) {
                $ninjaExe = $candidate
                break
            }
        }
    }
}

$cmakeGenerator = "NMake Makefiles"
$cmakeGeneratorArgs = @()
if ($ninjaExe) {
    $cmakeGenerator = "Ninja"
    $cmakeGeneratorArgs = @("-DCMAKE_MAKE_PROGRAM=$ninjaExe")
    Write-Host "Using Ninja: $ninjaExe"
} else {
    Write-Host "Warning: Ninja not found, falling back to NMake Makefiles"
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

Write-Host "=== Supertonic C++ JNI Build Script (PowerShell) ==="
Write-Host "Requested ABIs: $($requestedAbis -join ', ')"
Write-Host "Android SDK: $AndroidSdkRoot"
Write-Host "NDK: $ndkRoot"
Write-Host "ONNX Runtime JNI root: $OnnxJniRoot"

$cppDir = Join-Path $repoRoot "cpp\android"

foreach ($normalizedAbi in $requestedAbis) {
    $onnxAbiDir = Join-Path $OnnxJniRoot $normalizedAbi
    $onnxLib = Join-Path $onnxAbiDir "libonnxruntime.so"
    $jniLibsDir = Join-Path $repoRoot "android\app\src\main\jniLibs\$normalizedAbi"

    if (-not (Test-Path $onnxLib)) {
        throw "libonnxruntime.so was not found for ABI '$normalizedAbi' at $onnxLib"
    }

    if (-not (Test-SharedLibraryAlignment -ReadElfPath $readElf -LibraryPath $onnxLib)) {
        throw "libonnxruntime.so for ABI '$normalizedAbi' is not 16 KB aligned. Use onnxruntime-android 1.20.0 or newer."
    }

    Write-Host "[build] ABI=$normalizedAbi"
    Write-Host "        Using ONNX Runtime from: $onnxAbiDir"

    $buildDir = Join-Path $repoRoot "cpp\android\build-$normalizedAbi"
    New-Item -ItemType Directory -Force -Path $buildDir | Out-Null

    # CMake configure
    $cmakeArgs = @(
        "-S", $cppDir,
        "-B", $buildDir,
        "-DCMAKE_TOOLCHAIN_FILE=$ndkCmakeToolchain",
        "-DANDROID_ABI=$normalizedAbi",
        "-DANDROID_PLATFORM=android-$MinSdk",
        "-DANDROID_STL=c++_shared",
        "-DCMAKE_BUILD_TYPE=Release",
        "-DONNX_JNI_ROOT=$OnnxJniRoot",
        "-DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH",
        "-G", $cmakeGenerator
    ) + $cmakeGeneratorArgs

    & $cmakeExe @cmakeArgs

    if ($LASTEXITCODE -ne 0) {
        throw "CMake configure failed for ABI $normalizedAbi"
    }

    # CMake build
    & $cmakeExe --build $buildDir --config Release --target supertonic_tts -j

    if ($LASTEXITCODE -ne 0) {
        throw "CMake build failed for ABI $normalizedAbi"
    }

    $builtLib = Join-Path $buildDir "libsupertonic_tts.so"
    if (-not (Test-Path $builtLib)) {
        throw "Built library not found at $builtLib"
    }

    if (-not (Test-SharedLibraryAlignment -ReadElfPath $readElf -LibraryPath $builtLib)) {
        throw "Built library for ABI '$normalizedAbi' is not 16 KB aligned."
    }

    New-Item -ItemType Directory -Force -Path $jniLibsDir | Out-Null
    Copy-Item $builtLib $jniLibsDir -Force
    Copy-Item $onnxLib $jniLibsDir -Force

    # Copy the C++ STL shared library (libc++_shared.so) from the NDK
    $stlLib = Join-Path $ndkRoot "toolchains\llvm\prebuilt\windows-x86_64\sysroot\usr\lib"
    $abiStlMap = @{
        "arm64-v8a" = "aarch64-linux-android"
        "x86_64"    = "x86_64-linux-android"
    }
    $stlTarget = $abiStlMap[$normalizedAbi]
    if ($stlTarget) {
        $stlSo = Join-Path $stlLib "$stlTarget\libc++_shared.so"
        if (Test-Path $stlSo) {
            Copy-Item $stlSo $jniLibsDir -Force
            Write-Host "        Copied libc++_shared.so"
        }
    }

    Write-Host "        Copied:"
    Get-ChildItem $jniLibsDir | Select-Object Name, Length
}

Write-Host "JNI libraries are ready under android\app\src\main\jniLibs"
