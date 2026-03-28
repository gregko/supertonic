# Release Notes

## v1.2.5

Android-focused fork of [DevGitPit/supertonic](https://github.com/DevGitPit/supertonic),
which in turn tracks the original [supertone-inc/supertonic](https://github.com/supertone-inc/supertonic).
Maintained at [gregko/supertonic](https://github.com/gregko/supertonic).

### Changes from upstream

1. **Replaced Rust native libraries with C++ native libraries** — the C++ build
   for Android is simpler and requires less tooling; eliminates small code
   differences that existed between the Rust and C++ inference paths.

2. **Dual-ABI packaging (arm64-v8a + x86_64)** — single APK runs on phones and
   Windows Subsystem for Android (WSA).

3. **Renamed Android package** to `io.github.gregko.supertonic.tts`, with JNI
   aliases to match.

4. **System TTS engine** — the app registers as an Android TextToSpeech service,
   so any app (e.g. @Voice Aloud Reader, Moon+ Reader) can use Supertonic
   voices via the standard Android TTS API.

5. **Persist external TTS voice selections per language** — when used as a
   system TTS engine, selected voices are remembered per language.

6. **Added Temperature setting** to the app UI (v1.2.4).

7. **Persist Speed setting** — the speed slider value is now saved and restored
   across app restarts (was previously resetting to 1.1x on every launch).

8. **About dialog and fork version metadata** — clearly identifies the fork
   build and version.

9. **Bundled third-party notices and licenses screen** — ONNX Runtime,
   Supertonic model (OpenRAIL-M), and source code (MIT) licenses accessible
   from within the app.

10. **Android build and release workflow** — GitHub Actions CI pipeline for
    automated release builds; documented update workflow for upstream sync,
    model updates, and dependency bumps.

11. **Gradle wrapper included** — repo is self-contained, no separate Gradle
    install needed.

### Known issues

- **Word/syllable swallowing** — words are randomly omitted during synthesis.
  Affects all voices, most noticeable when reading longer natural text (ebooks,
  dialogue). Increasing speed (e.g. 1.4x) reduces the frequency but does not
  eliminate it. Changing quality (diffusion steps) or temperature has no
  meaningful effect. This appears to be an upstream model issue. See
  [supertone-inc/supertonic#83](https://github.com/supertone-inc/supertonic/issues/83).
