#!/usr/bin/env bash
set -euo pipefail

MODEL_NAME="ggml-tiny.en.bin"
MODEL_URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$MODEL_NAME"
EXPECTED_SHA1="c78c86eb1a8faa21b369bcd33207cc90d64ae9df"
CACHE_DIR="${SURVEYCORE_MODEL_CACHE:-$HOME/.cache/surveycore/models}"
MODEL_PATH="$CACHE_DIR/$MODEL_NAME"
TEMP_PATH="$MODEL_PATH.part"

if ! command -v curl >/dev/null 2>&1; then
    echo "ERROR: curl is required." >&2
    exit 1
fi

mkdir -p "$CACHE_DIR"

verify_model() {
    local file="$1"

    if [ ! -s "$file" ]; then
        return 1
    fi

    if command -v shasum >/dev/null 2>&1; then
        local actual_sha1
        actual_sha1="$(shasum -a 1 "$file" | awk '{print $1}')"

        if [ "$actual_sha1" != "$EXPECTED_SHA1" ]; then
            echo "SHA-1 mismatch:" >&2
            echo "  expected: $EXPECTED_SHA1" >&2
            echo "  actual:   $actual_sha1" >&2
            return 1
        fi
    fi

    return 0
}

if verify_model "$MODEL_PATH"; then
    echo "Model already ready: $MODEL_PATH"
    exit 0
fi

rm -f "$TEMP_PATH"

echo "Downloading $MODEL_NAME"
echo "Destination: $MODEL_PATH"

curl \
    -fL \
    --retry 5 \
    --retry-delay 2 \
    --connect-timeout 20 \
    -o "$TEMP_PATH" \
    "$MODEL_URL"

mv -f "$TEMP_PATH" "$MODEL_PATH"

if ! verify_model "$MODEL_PATH"; then
    rm -f "$MODEL_PATH"
    echo "ERROR: downloaded model failed verification." >&2
    exit 1
fi

echo "Model ready: $MODEL_PATH"
