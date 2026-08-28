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
