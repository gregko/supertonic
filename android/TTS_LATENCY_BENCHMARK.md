# Android TTS Latency Benchmark

Supertonic emits structured `TTS_METRIC` logcat lines for both native synthesis and the Android
system TTS service. The benchmark test emits `TTS_BENCH` lines for diffusion steps 2, 3, and 5
across ONNX intra-op thread counts 1, 2, 3, 4, 5, 6, and 8.

## Run the inference matrix

Connect one Android device or WSA instance, then run from `android/`:

```powershell
adb logcat -c
.\gradlew.bat connectedDebugAndroidTest `
  "-Pandroid.testInstrumentationRunnerArguments.class=io.github.gregko.supertonic.tts.SynthesisBenchmarkTest"
adb logcat -d -s SupertonicTTS:I SupertonicBenchmark:I *:S
```

The first synthesis at each thread count is an unmeasured warm-up. Each measured configuration
runs twice. `end_to_end_rtf` and native `rtf` use the conventional definition:

```text
RTF = synthesis time / generated audio duration
```

Lower is better. `throughput` is its inverse and is reported separately.

### WSA reference run (2026-07-14)

The connected Android 13 WSA device reported the following mean end-to-end synthesis times for a
5.395-second English sample (two measured repetitions per cell, after warm-up):

| ONNX threads | 2 steps | 3 steps | 5 steps |
|---:|---:|---:|---:|
| 1 | 740 ms | 908 ms | 1,415 ms |
| 2 | 417 ms | 606 ms | 808 ms |
| 3 | 435 ms | 591 ms | 817 ms |
| 4 | 397 ms | 547 ms | 861 ms |
| 5 | 387 ms | 527 ms | 818 ms |
| 6 | 414 ms | 612 ms | 856 ms |
| 8 | 388 ms | 543 ms | 849 ms |

Five threads remains the default: it was competitive on WSA, while device CPU topology and
thermal behavior can change the optimum. Five diffusion steps also remains the quality default.

## Compare external-client transitions

1. Select Supertonic as the Android TTS engine.
2. Clear logcat and play the same multi-sentence passage from the same client.
3. Capture `SupertonicTTS:I` lines.
4. Compare these fields between builds:

- `inference_ms`: native model execution only
- `pcm_conversion_ms`: float-to-PCM conversion only
- `callback_backpressure_ms`: time inside Android PCM callbacks
- `time_to_first_audio_ms`: request/JNI entry to first PCM submission
- `estimated_transition_gap_ms`: current first PCM submission minus the prior request's estimated
  playback end
- `style_ms` and `cache_hit`: parsed voice-style cache behavior

`estimated_transition_gap_ms` is comparative, not an acoustic measurement. Android's
`SynthesisCallback` does not expose its playback head, so the estimate assumes playback begins at
the first successful PCM submission and advances at the declared sample rate.

## Quality policy

The shipped default remains five diffusion steps. Use the matrix to identify viable performance
settings, then listen to identical samples—especially sentence endings, names, numbers, and
punctuation—before changing that default. The streaming service keeps each complete sentence as a
single model input; PCM delivery segmentation therefore does not alter model-level sentence
prosody.

## Android queue constraint

System TTS requests are dispatched serially. If `audioAvailable()` blocks until only a small amount
of audio remains buffered, the next queued request cannot start inference until the current
callback finishes. Same-thread segmented delivery removes full-waveform PCM copies and measures
this backpressure, but cross-request look-ahead would require cooperation from the client or a
different Android framework contract.

In the WSA reference queue run at five threads/five steps, the first request measured 827 ms of
native inference and 4,958 ms of callback backpressure for 5.630 seconds of audio. The second
request hit the style cache, measured 765 ms of native inference, and submitted its first PCM with
an estimated 103 ms transition gap. Parsed style lookup fell from 15.5 ms cold to approximately
0.001 ms cached.
