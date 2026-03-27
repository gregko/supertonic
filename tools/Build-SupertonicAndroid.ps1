param(
    [string[]]$Abi = @("arm64-v8a", "x86_64"),
    [string]$OnnxJniRoot = (Join-Path $HOME "onnxruntime-android\jni"),
    [switch]$SkipLockCheck
)

. (Join-Path $PSScriptRoot "Supertonic.AndroidUpdate.Common.ps1")

$abiFilters = Normalize-AbiList -AbiList $Abi
if (-not $abiFilters) {
    throw "No ABI filters were provided."
}

if (-not $SkipLockCheck) {
    $lock = Read-JsonFile -Path (Get-AndroidInputsLockPath)
    if ($lock) {
        $issues = @(Compare-AndroidInputsToLock -Lock $lock -OnnxJniRoot $OnnxJniRoot)
        if ($issues.Count -gt 0) {
            throw "Current local inputs do not match the committed lock file.`n - $($issues -join "`n - ")"
        }
    } else {
        Write-Warning "No Android input lock file is present. The build will proceed without a baseline check."
    }
}

$repoRoot = Get-SupertonicRepoRoot
$androidDir = Join-Path $repoRoot "android"
$abiArg = $abiFilters -join ","

Push-Location $androidDir
try {
    & .\gradlew.bat assembleDebug "-PsupertonicAbiFilters=$abiArg" "-PsupertonicOnnxJniRoot=$OnnxJniRoot"
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed."
    }
} finally {
    Pop-Location
}

$apkPath = Join-Path $repoRoot "android\app\build\outputs\apk\debug\app-debug.apk"
$entries = @(Get-ApkEntryNames -ApkPath $apkPath)
$missingEntries = New-Object System.Collections.Generic.List[string]

foreach ($abiName in $abiFilters) {
    foreach ($libraryName in @("libonnxruntime.so", "libsupertonic_tts.so")) {
        $entryName = "lib/$abiName/$libraryName"
        if ($entries -notcontains $entryName) {
            $missingEntries.Add($entryName)
        }
    }
}

if ($missingEntries.Count -gt 0) {
    throw "APK is missing native libraries:`n - $($missingEntries -join "`n - ")"
}

$zipalignPath = Get-BuildToolsZipalignPath -AndroidSdkRoot (Get-AndroidSdkRoot)
if (-not $zipalignPath) {
    throw "zipalign.exe was not found under the Android SDK build-tools directory."
}

& $zipalignPath -c -P 16 4 $apkPath
if ($LASTEXITCODE -ne 0) {
    throw "zipalign validation failed for $apkPath"
}

Write-Host "Android build and validation succeeded."
Write-Host "APK:"
Write-Host "  $apkPath"
