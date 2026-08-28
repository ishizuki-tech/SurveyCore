# ASR Test App

Standalone Android application module for comparing the existing `whisper.cpp`
backend with true streaming English ASR using `sherpa-onnx`.

It intentionally does not depend on SurveyEngine, SurveyController, LiteRT-LM,
or llama.cpp.

## Package

`com.negi.whispertest`

## Test modes

- Bundled deterministic 16 kHz mono PCM16 WAV with `whisper.cpp`
- 5x `whisper.cpp` benchmark
- Batch microphone capture with `whisper.cpp`
- Experimental rolling-window Whisper live transcription
- True streaming English transcription with sherpa-onnx Streaming Zipformer

## Whisper models

The Whisper modes expect:

```text
files/models/ggml-base.en.bin
files/models/ggml-tiny.en.bin
```

## Sherpa English streaming model

The Sherpa modes compare two English streaming models:

```text
sherpa-onnx-streaming-zipformer-en-20M-2023-02-17
sherpa-onnx-streaming-zipformer-en-2023-06-26
```

The 20M model is the lightweight speed-first baseline. The 2023-06-26 model
is larger and is included to test whether recognition accuracy improves while
keeping true stateful streaming.

with these runtime files:

```text
encoder-epoch-99-avg-1.int8.onnx
decoder-epoch-99-avg-1.onnx
joiner-epoch-99-avg-1.int8.onnx
tokens.txt
```

The model is English-only and is designed for streaming transducer decoding.
Audio is fed incrementally in 100 ms chunks; previous audio is not repeatedly
re-transcribed as it is in the Whisper live experiment.

## Sherpa setup

Download the official Android AAR and model:

```bash
./scripts/download_sherpa_onnx_android_aar.sh
./scripts/download_sherpa_streaming_en.sh
./scripts/download_sherpa_streaming_en_better.sh
```

The AAR is cached outside the repository at:

```text
~/.cache/surveycore/libs/sherpa-onnx-1.13.4.aar
```

The model is cached at:

```text
~/.cache/surveycore/models/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/
~/.cache/surveycore/models/sherpa-onnx-streaming-zipformer-en-2023-06-26/
```

Install the debug APK, then copy the model into the app-private sandbox:

```bash
./gradlew :whisper-test-app:installDebug
./scripts/install_sherpa_streaming_en_test_app.sh
./scripts/install_sherpa_streaming_en_better_test_app.sh
./scripts/run_whisper_test_app.sh
```

After installing the model, restart the app and use:

```text
Start Sherpa 20M Streaming
Start Sherpa Better English
```

## Release test

The existing local release signing configuration can be used for device testing:

```bash
./gradlew :whisper-test-app:assembleRelease
./gradlew :whisper-test-app:installRelease
adb shell am start -n com.negi.whispertest/.WhisperTestActivity
```

Install the model while the debug build is installed first, because the helper
script uses `run-as` to copy files into the app-private storage. Installing the
release APK over the same application ID keeps that private data when the
signing key is the same.

## Checkpoint: Nemotron streaming ASR

Stable local checkpoint:

```text
commit: c209c55
tag: sherpa-nemotron-checkpoint-1
branch: main
```

This checkpoint captures the current standalone Android ASR test environment before
integrating streaming ASR into the main SurveyCore application.

### Current preferred streaming configuration

The current first-choice streaming recognizer is:

```text
sherpa-onnx
NVIDIA Nemotron Speech Streaming English 0.6B
1120 ms INT8
```

Device-side model directory:

```text
files/models/sherpa-onnx-nemotron-speech-streaming-en-0.6b-1120ms-int8-2026-04-25/
```

Required runtime files:

```text
encoder.int8.onnx
decoder.int8.onnx
joiner.int8.onnx
tokens.txt
```

Current runtime settings:

```text
sample rate: 16000 Hz
audio: mono PCM16
streaming chunk: 100 ms
CPU threads: 4
decoder: greedy_search
endpoint trailing silence: ~1.0 sec
```

Sherpa uses `OnlineRecognizer` with a persistent stateful `OnlineStream`. Unlike
the Whisper live experiment, previously processed audio is not repeatedly
re-transcribed from a rolling waveform window.

### Warm runtime

Nemotron is prepared when the test application starts.

The startup path is:

```text
App start
  -> create/load OnlineRecognizer
  -> feed 1.6 sec silence warm-up audio
  -> run initial decode()
  -> release warm-up stream
  -> keep OnlineRecognizer resident
  -> SHERPA READY
```

Starting microphone streaming after warm-up only needs a new `OnlineStream` and
`AudioRecord`.

Stopping a streaming session releases:

```text
OnlineStream
AudioRecord
```

but keeps the `OnlineRecognizer` warm. The recognizer is released when the
Activity is destroyed.

Whisper initialization runs after the preferred Sherpa runtime is prepared.

### Models evaluated

The streaming experiments included:

```text
Streaming Zipformer English 20M INT8
Streaming Zipformer English 2023-06-26 INT8
Streaming Zipformer English 2023-06-21 INT8
Streaming Zipformer English 2023-06-21 FP32
Nemotron Speech Streaming English 0.6B 560ms INT8
Nemotron Speech Streaming English 0.6B 1120ms INT8
```

Current result:

```text
Nemotron 1120ms INT8  <- preferred accuracy/streaming candidate
Nemotron 560ms INT8   <- lower-latency comparison candidate
Zipformer             <- no longer the preferred path
Whisper live          <- retained as an experiment, not preferred for streaming
```

The 1120 ms Nemotron variant produced noticeably better sentence structure than
the 560 ms and Zipformer tests while continuing to run as true stateful
streaming. It is not perfect, so accuracy evaluation should continue before the
main SurveyCore integration is finalized.

### Whisper test modes retained

`whisper.cpp` remains available for comparison and batch/final transcription.

Models:

```text
files/models/ggml-tiny.en.bin
files/models/ggml-base.en.bin
```

Current Whisper test modes:

- bundled deterministic WAV
- 5x benchmark
- microphone batch transcription
- experimental near-real-time rolling-window live transcription

The Whisper live implementation uses VAD and rolling windows. It should not be
treated as true incremental streaming.

### sherpa-onnx Android runtime

The Android AAR is kept outside the repository:

```text
~/.cache/surveycore/libs/sherpa-onnx-1.13.4.aar
```

The Gradle module references that local AAR.

Download:

```bash
./scripts/download_sherpa_onnx_android_aar.sh
```

### Nemotron model setup

Download the preferred 1120 ms model:

```bash
./scripts/download_sherpa_nemotron_1120ms.sh
```

Download the 560 ms comparison model:

```bash
./scripts/download_sherpa_nemotron_560ms.sh
```

The downloaded models are cached under:

```text
~/.cache/surveycore/models/
```

Because the model installer uses `run-as`, install the debug APK before copying
models into the application-private sandbox:

```bash
./gradlew :whisper-test-app:installDebug

./scripts/install_sherpa_nemotron_1120ms_test_app.sh
./scripts/install_sherpa_nemotron_560ms_test_app.sh
```

Then a locally signed release APK can replace the debug build while preserving
the app-private model files:

```bash
./gradlew :whisper-test-app:assembleRelease
./gradlew :whisper-test-app:installRelease

adb shell am start -n \
  com.negi.whispertest/.WhisperTestActivity
```

### Current ASR Playground UI

The standalone test application UI is now organized as:

```text
ASR Playground

STATUS
STREAMING
WHISPER TOOLS
TRANSCRIPT
RUNTIME & MODELS
```

The runtime card reports the active Sherpa model, model files, installed state,
CPU thread count, chunk size, endpoint settings, Whisper models, and
`whisper.cpp` native capabilities.

The application uses system-bar insets so content does not render underneath the
status or navigation bars.

### Important implementation files

```text
whisper-test-app/src/main/java/com/negi/whispertest/WhisperTestActivity.kt
whisper-test-app/src/main/java/com/negi/whispertest/SherpaStreamingController.kt
whisper-test-app/src/main/java/com/negi/whispertest/LiveTranscriptionController.kt
whisper-test-app/src/main/java/com/negi/whispertest/LivePartialTranscriptMerger.kt

asr-whispercpp/src/main/java/com/negi/surveycore/asr/whispercpp/WhisperCppBackend.kt
```

Model files and the sherpa-onnx AAR are intentionally not committed to Git.

### Next evaluation step

Before moving streaming ASR into the production SurveyCore application, perform
a controlled A/B test:

```text
record one microphone sample once
        |
        +--> Nemotron 560ms
        |
        +--> Nemotron 1120ms
```

Feed the exact same PCM samples to each recognizer sequentially and compare:

```text
transcript
first-partial latency
decode time
RTF
accuracy
```

Do not keep both 0.6B recognizers resident at the same time during this test;
load, evaluate, and release them sequentially to avoid unnecessary memory
pressure.

### Restore this checkpoint

Inspect:

```bash
git show --stat sherpa-nemotron-checkpoint-1
```

Temporarily check out the exact checkpoint:

```bash
git switch --detach sherpa-nemotron-checkpoint-1
```

Checkpoint identity:

```text
c209c55
checkpoint: stable Nemotron streaming ASR with warm runtime UI
```
