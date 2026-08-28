#!/usr/bin/env bash
set -euo pipefail

VERSION="1.13.4"
AAR_NAME="sherpa-onnx-$VERSION.aar"
AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VERSION/$AAR_NAME"
EXPECTED_SHA256="03f9c4df965f21c71269365a7951a7f23b5696fddd093fa318c80d65550ab780"
CACHE_DIR="${SURVEYCORE_LIB_CACHE:-$HOME/.cache/surveycore/libs}"
AAR_PATH="$CACHE_DIR/$AAR_NAME"
TEMP_PATH="$AAR_PATH.part"

if ! command -v curl >/dev/null 2>&1; then
    echo "ERROR: curl is required." >&2
    exit 1
fi

mkdir -p "$CACHE_DIR"

sha256_of() {
    local file="$1"

    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$file" | awk '{print $1}'
    elif command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$file" | awk '{print $1}'
    else
        echo "ERROR: shasum or sha256sum is required." >&2
        exit 1
    fi
}

verify_aar() {
    local file="$1"

    if [ ! -s "$file" ]; then
        return 1
    fi

    local actual_sha256
    actual_sha256="$(sha256_of "$file")"

    if [ "$actual_sha256" != "$EXPECTED_SHA256" ]; then
        echo "SHA-256 mismatch:" >&2
        echo "  expected: $EXPECTED_SHA256" >&2
        echo "  actual:   $actual_sha256" >&2
        return 1
    fi

    return 0
}

if verify_aar "$AAR_PATH"; then
    echo "sherpa-onnx Android AAR already ready: $AAR_PATH"
    exit 0
fi

rm -f "$TEMP_PATH"

echo "Downloading $AAR_NAME"
echo "Destination: $AAR_PATH"

curl \
    -fL \
    --retry 5 \
    --retry-delay 2 \
    --connect-timeout 20 \
    -o "$TEMP_PATH" \
    "$AAR_URL"

mv -f "$TEMP_PATH" "$AAR_PATH"

if ! verify_aar "$AAR_PATH"; then
    rm -f "$AAR_PATH"
    echo "ERROR: downloaded AAR failed verification." >&2
    exit 1
fi

echo "sherpa-onnx Android AAR ready: $AAR_PATH"
