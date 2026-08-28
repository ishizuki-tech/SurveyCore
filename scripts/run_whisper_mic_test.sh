#!/usr/bin/env bash
set -euo pipefail

PACKAGE_NAME="${SURVEYCORE_PACKAGE:-com.negi.surveycore}"
ACTIVITY_NAME="$PACKAGE_NAME/.asrtest.WhisperCppMicActivity"

if ! command -v adb >/dev/null 2>&1; then
    echo "ERROR: adb is required." >&2
    exit 1
fi

adb logcat -c
adb shell am start -W -n "$ACTIVITY_NAME"

echo
echo "Whisper microphone test launched."
echo "Tap Start recording, allow microphone permission, speak English, then tap Stop."
echo
echo "Relevant logcat:"
echo "  adb logcat -s WhisperCppMic:D WhisperCppNative:I '*:S'"
