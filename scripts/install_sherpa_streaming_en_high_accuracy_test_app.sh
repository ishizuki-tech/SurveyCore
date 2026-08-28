#!/usr/bin/env bash
set -euo pipefail

PACKAGE_NAME="${WHISPER_TEST_PACKAGE:-com.negi.whispertest}"
MODEL_DIR_NAME="sherpa-onnx-streaming-zipformer-en-2023-06-21"
CACHE_DIR="${SURVEYCORE_MODEL_CACHE:-$HOME/.cache/surveycore/models}"
MODEL_DIR="$CACHE_DIR/$MODEL_DIR_NAME"
APP_MODEL_DIR="files/models/$MODEL_DIR_NAME"
TEMP_DEVICE_DIR="/data/local/tmp/$MODEL_DIR_NAME"

REQUIRED_FILES=(
    "encoder-epoch-99-avg-1.int8.onnx"
    "decoder-epoch-99-avg-1.onnx"
    "joiner-epoch-99-avg-1.int8.onnx"
    "tokens.txt"
)

if ! command -v adb >/dev/null 2>&1; then
    echo "ERROR: adb is required." >&2
    exit 1
fi

for name in "${REQUIRED_FILES[@]}"; do
    if [ ! -s "$MODEL_DIR/$name" ]; then
        echo "ERROR: model file not found: $MODEL_DIR/$name" >&2
        echo "Run ./scripts/download_sherpa_streaming_en_high_accuracy.sh first." >&2
        exit 1
    fi
done

if ! adb get-state >/dev/null 2>&1; then
    echo "ERROR: no adb device is ready." >&2
    exit 1
fi

if ! adb shell "run-as $PACKAGE_NAME true" >/dev/null 2>&1; then
    echo "ERROR: run-as failed for $PACKAGE_NAME." >&2
    echo "Install the ASR Test debug APK first:" >&2
    echo "  ./gradlew :whisper-test-app:installDebug" >&2
    exit 1
fi

adb shell "mkdir -p '$TEMP_DEVICE_DIR'"
adb shell "run-as $PACKAGE_NAME mkdir -p '$APP_MODEL_DIR'"

for name in "${REQUIRED_FILES[@]}"; do
    echo "Installing $name ..."
    adb push "$MODEL_DIR/$name" "$TEMP_DEVICE_DIR/$name" >/dev/null
    adb shell "cat '$TEMP_DEVICE_DIR/$name' | run-as $PACKAGE_NAME sh -c 'cat > $APP_MODEL_DIR/$name'"
done

adb shell "rm -rf '$TEMP_DEVICE_DIR'"

echo "Installed Sherpa High Accuracy English streaming model:"
adb shell "run-as $PACKAGE_NAME ls -lh '$APP_MODEL_DIR'"
