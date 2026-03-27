# Android Update Workflow

This repository now has a low-risk Android update workflow with three separate operations:

1. Rebuild the current locked state
2. Import new model assets or ONNX Runtime binaries as a candidate change
3. Promote an accepted candidate into the committed lock file

The key rule is simple:

- A plain rebuild does not fetch anything from upstream.
- It uses only the local repo files in `assets/` plus the local ONNX Runtime Android install under `%USERPROFILE%\onnxruntime-android`.
- The committed baseline is recorded in [tools/android-inputs.lock.json](/C:/GitHub/supertonic/tools/android-inputs.lock.json).

## What Is Locked

The lock file records:

- all Android model assets under `assets/onnx` and `assets/voice_styles`
- the local ONNX Runtime Android JNI libraries for `arm64-v8a` and `x86_64`
- the pinned Rust `ort` crate and vendored path
- the default Android ABIs and NDK version used by this fork

Use this to answer one question quickly:

- "Am I rebuilding the known-good inputs, or did something change locally?"

## Fast Path: Rebuild Known-Good State

Run:

```powershell
cd C:\GitHub\supertonic
.\tools\Get-SupertonicAndroidStatus.ps1
.\tools\Build-SupertonicAndroid.ps1
```

`Build-SupertonicAndroid.ps1` verifies:

- current local assets match the committed lock file
- current local ONNX Runtime libs match the committed lock file
- the APK contains both `libonnxruntime.so` and `libsupertonic_tts.so` for each ABI
- the final APK passes `zipalign -c -P 16`

## Safe Upgrade Rules

Do not combine all update types at once.

Use separate branches or separate commits for:

- upstream app code updates
- model asset updates
- ONNX Runtime Android updates

That makes regressions attributable.

## Update Type 1: Upstream App Code

Do this manually with Git. Do not automate merges of upstream repo code into this fork.

Recommended flow:

```powershell
cd C:\GitHub\supertonic
git fetch --all --prune
git switch -c codex/upstream-sync-YYYYMMDD
```

Then merge or rebase the upstream code you want to test, rebuild with:

```powershell
.\tools\Build-SupertonicAndroid.ps1
```

Why this stays manual:

- this fork has Android-specific patches
- automatic upstream merges are the highest-risk update class
- code updates should be reviewed separately from model/runtime changes

## Update Type 2: Model Assets

First obtain the candidate asset tree locally. The source can be:

- a Hugging Face clone
- an extracted download
- another checkout containing `onnx/` and `voice_styles/`

Then import it into this repo:

```powershell
cd C:\GitHub\supertonic
.\tools\Import-SupertonicAssets.ps1 -SourceRoot C:\path\to\supertonic-2
```

What this script does:

- detects a compatible asset layout
- backs up the current repo assets into `tmp\asset-backups\<timestamp>\`
- replaces `assets\onnx` and `assets\voice_styles`
- leaves the committed lock file unchanged so you can evaluate the candidate safely

Evaluate the candidate build:

```powershell
.\tools\Build-SupertonicAndroid.ps1 -SkipLockCheck
```

If the candidate is good, promote it into the lock file:

```powershell
.\tools\Update-AndroidInputsLock.ps1 -AssetSource "huggingface.co/Supertone/supertonic-2@<commit-or-tag>"
```

## Update Type 3: ONNX Runtime Android

Download and stage a specific version:

```powershell
cd C:\GitHub\supertonic
.\tools\Update-OnnxRuntimeAndroid.ps1 -Version 1.20.0
```

What this script does:

- downloads the AAR from Maven Central
- extracts it into `tmp\onnxruntime-android-<version>`
- backs up the current `%USERPROFILE%\onnxruntime-android`
- replaces the local ONNX Runtime Android install
- leaves the committed lock file unchanged until you accept the upgrade

Evaluate the candidate build:

```powershell
.\tools\Build-SupertonicAndroid.ps1 -SkipLockCheck
```

If the candidate is good, promote it into the lock file:

```powershell
.\tools\Update-AndroidInputsLock.ps1 -OnnxRuntimeVersion 1.20.0
```

## Recommended Full Upgrade Sequence

For a model or runtime upgrade, use this exact order:

1. Check current status

```powershell
.\tools\Get-SupertonicAndroidStatus.ps1
```

2. Import one candidate change only

```powershell
.\tools\Import-SupertonicAssets.ps1 -SourceRoot C:\path\to\candidate
```

or

```powershell
.\tools\Update-OnnxRuntimeAndroid.ps1 -Version 1.20.0
```

3. Build and test the candidate without changing the lock yet

```powershell
.\tools\Build-SupertonicAndroid.ps1 -SkipLockCheck
```

4. Test on device

Check at least:

- English
- Spanish
- one external TTS client such as `@Voice`
- voice switching
- first-run asset install
- 16 KB alignment warnings

5. If accepted, update the lock file

```powershell
.\tools\Update-AndroidInputsLock.ps1
```

6. Commit the code change and the lock file together

This gives you a clean diff showing exactly what changed.

## Rollback

The update scripts create backups under `tmp\`.

Typical rollback locations:

- model assets: `tmp\asset-backups\<timestamp>\`
- ONNX Runtime Android: `tmp\onnxruntime-backups\<timestamp>\`

You can restore from those backups, then rebuild.

## Notes

- `Build-SupertonicAndroid.ps1` uses the same Gradle and JNI build path as Android Studio.
- The Rust `ort` dependency is still pinned in [rust/Cargo.toml](/C:/GitHub/supertonic/rust/Cargo.toml), so a plain `cargo` or Gradle rebuild will not automatically pick up new `ort` releases.
- Rebuilds do not automatically pull upstream GitHub repo changes, Hugging Face model updates, or Maven runtime updates.
