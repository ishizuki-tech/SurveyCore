#!/usr/bin/env bash
set -euo pipefail

PACKAGE_NAME="${SURVEYCORE_PACKAGE:-com.negi.surveycore}"
ACTIVITY_NAME="$PACKAGE_NAME/.asrtest.WhisperCppSmokeActivity"

if ! command -v adb >/dev/null 2>&1; then
    echo "ERROR: adb is required." >&2
    exit 1
fi

adb logcat -c
adb shell am start -n "$ACTIVITY_NAME"

echo
echo "Whisper smoke test launched. Relevant logcat:"
echo "  adb logcat -s WhisperCppSmoke:D WhisperCppNative:I '*:S'"
