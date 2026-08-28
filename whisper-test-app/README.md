# Whisper Test App

Standalone Android application module for validating the `asr-whispercpp` backend.

It intentionally does not depend on SurveyEngine, SurveyController, LiteRT-LM, or llama.cpp.

## Package

`com.negi.whispertest`

## Tests

- Bundled deterministic 16 kHz mono PCM16 WAV
- Live microphone capture using `AudioRecord`

## Model

The app expects:

`files/models/ggml-base.en.bin`

Because this is a separate Android application package, it has a separate private app sandbox from `com.negi.surveycore`.

Install the debug APK and copy the already-downloaded model into the test app:

```bash
./gradlew :whisper-test-app:installDebug
./scripts/install_whisper_base_en_test_app.sh
./scripts/run_whisper_test_app.sh
```
