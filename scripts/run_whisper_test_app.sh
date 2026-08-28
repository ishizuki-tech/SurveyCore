#!/usr/bin/env bash
set -euo pipefail

PACKAGE_NAME="${WHISPER_TEST_PACKAGE:-com.negi.whispertest}"
ACTIVITY_NAME="$PACKAGE_NAME/.WhisperTestActivity"

if ! command -v adb >/dev/null 2>&1; then
    echo "ERROR: adb is required." >&2
    exit 1
fi

adb shell am start -W -n "$ACTIVITY_NAME"

echo
echo "Whisper Test launched."
echo "Use either the bundled WAV test or the microphone test."
echo
echo "Relevant logcat:"
echo "  adb logcat -s WhisperTest:D WhisperCppNative:I '*:S'"
