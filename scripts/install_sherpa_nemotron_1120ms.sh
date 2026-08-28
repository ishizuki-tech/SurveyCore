#!/usr/bin/env bash
set -euo pipefail

PACKAGE_NAME="${SURVEYCORE_PACKAGE:-com.negi.surveycore}"
MODEL_NAME="sherpa-onnx-nemotron-speech-streaming-en-0.6b-1120ms-int8-2026-04-25"
MODEL_DIR="${SURVEYCORE_MODEL_CACHE:-$HOME/.cache/surveycore/models}/$MODEL_NAME"
APP_MODEL_DIR="files/models/$MODEL_NAME"
TEMP_DIR="/data/local/tmp/surveycore-nemotron-1120"

if ! command -v adb >/dev/null 2>&1; then
    echo "ERROR: adb is required." >&2
    exit 1
fi

for name in encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt; do
    if [[ ! -s "$MODEL_DIR/$name" ]]; then
        echo "ERROR: missing model file: $MODEL_DIR/$name" >&2
        echo "Run ./scripts/download_sherpa_nemotron_1120ms.sh first." >&2
        exit 1
    fi
done

if ! adb shell "run-as $PACKAGE_NAME true" >/dev/null 2>&1; then
    echo "ERROR: run-as failed for $PACKAGE_NAME." >&2
    echo "Install the SurveyCore debug APK first:" >&2
    echo "  ./gradlew :app:installDebug" >&2
    exit 1
fi

adb shell "rm -rf '$TEMP_DIR' && mkdir -p '$TEMP_DIR'"
adb shell "run-as $PACKAGE_NAME mkdir -p '$APP_MODEL_DIR'"

for name in encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt; do
    echo "Installing $name..."
    adb push "$MODEL_DIR/$name" "$TEMP_DIR/$name" >/dev/null
    adb shell         "cat '$TEMP_DIR/$name' | run-as $PACKAGE_NAME sh -c 'cat > $APP_MODEL_DIR/$name'"
done

adb shell "rm -rf '$TEMP_DIR'"

echo
echo "Installed Nemotron 1120ms model into SurveyCore:"
adb shell "run-as $PACKAGE_NAME ls -lh '$APP_MODEL_DIR'"
