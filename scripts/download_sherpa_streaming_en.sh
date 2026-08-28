#!/usr/bin/env bash
set -euo pipefail

MODEL_DIR_NAME="sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
ARCHIVE_NAME="$MODEL_DIR_NAME.tar.bz2"
MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$ARCHIVE_NAME"
CACHE_DIR="${SURVEYCORE_MODEL_CACHE:-$HOME/.cache/surveycore/models}"
MODEL_DIR="$CACHE_DIR/$MODEL_DIR_NAME"
ARCHIVE_PATH="$CACHE_DIR/$ARCHIVE_NAME"
TEMP_PATH="$ARCHIVE_PATH.part"

REQUIRED_FILES=(
    "encoder-epoch-99-avg-1.int8.onnx"
    "decoder-epoch-99-avg-1.onnx"
    "joiner-epoch-99-avg-1.int8.onnx"
    "tokens.txt"
)

if ! command -v curl >/dev/null 2>&1; then
    echo "ERROR: curl is required." >&2
    exit 1
fi

if ! command -v tar >/dev/null 2>&1; then
    echo "ERROR: tar is required." >&2
    exit 1
fi

model_ready() {
    local name
    for name in "${REQUIRED_FILES[@]}"; do
        if [ ! -s "$MODEL_DIR/$name" ]; then
            return 1
        fi
    done
    return 0
}

mkdir -p "$CACHE_DIR"

if model_ready; then
    echo "Sherpa English streaming model already ready: $MODEL_DIR"
    exit 0
fi

rm -rf "$MODEL_DIR"
rm -f "$ARCHIVE_PATH" "$TEMP_PATH"

echo "Downloading $MODEL_DIR_NAME"
echo "Destination cache: $CACHE_DIR"

curl \
    -fL \
    --retry 5 \
    --retry-delay 2 \
    --connect-timeout 20 \
    -o "$TEMP_PATH" \
    "$MODEL_URL"

mv -f "$TEMP_PATH" "$ARCHIVE_PATH"
tar -xjf "$ARCHIVE_PATH" -C "$CACHE_DIR"
rm -f "$ARCHIVE_PATH"

if ! model_ready; then
    echo "ERROR: extracted model is missing one or more required files." >&2
    exit 1
fi

echo "Sherpa English streaming model ready: $MODEL_DIR"
for name in "${REQUIRED_FILES[@]}"; do
    ls -lh "$MODEL_DIR/$name"
done
