#!/usr/bin/env bash
set -euo pipefail

PACKAGE_NAME="${SURVEYCORE_PACKAGE:-com.negi.surveycore}"
MODEL_NAME="ggml-base.en.bin"
CACHE_DIR="${SURVEYCORE_MODEL_CACHE:-$HOME/.cache/surveycore/models}"
MODEL_PATH="$CACHE_DIR/$MODEL_NAME"
TEMP_DEVICE_PATH="/data/local/tmp/$MODEL_NAME"
APP_MODEL_PATH="files/models/$MODEL_NAME"

if ! command -v adb >/dev/null 2>&1; then
    echo "ERROR: adb is required." >&2
    exit 1
fi

if [ ! -s "$MODEL_PATH" ]; then
    echo "ERROR: model not found: $MODEL_PATH" >&2
    echo "Run scripts/download_whisper_base_en.sh first." >&2
    exit 1
fi

if ! adb get-state >/dev/null 2>&1; then
    echo "ERROR: no adb device is ready." >&2
    exit 1
fi

if ! adb shell "run-as $PACKAGE_NAME true" >/dev/null 2>&1; then
    echo "ERROR: run-as failed for $PACKAGE_NAME." >&2
    echo "Install the SurveyCore debug APK first." >&2
    exit 1
fi

echo "Pushing model to device temporary storage..."
adb push "$MODEL_PATH" "$TEMP_DEVICE_PATH"

adb shell "run-as $PACKAGE_NAME mkdir -p files/models"
adb shell "cat '$TEMP_DEVICE_PATH' | run-as $PACKAGE_NAME sh -c 'cat > $APP_MODEL_PATH'"
adb shell "rm -f '$TEMP_DEVICE_PATH'"

echo "Installed model:"
adb shell "run-as $PACKAGE_NAME ls -lh '$APP_MODEL_PATH'"
