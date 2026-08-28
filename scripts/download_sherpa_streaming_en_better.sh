#!/usr/bin/env bash
set -euo pipefail

MODEL_DIR_NAME="sherpa-onnx-streaming-zipformer-en-2023-06-26"
MODEL_REPO="https://huggingface.co/csukuangfj/$MODEL_DIR_NAME/resolve/main"
CACHE_DIR="${SURVEYCORE_MODEL_CACHE:-$HOME/.cache/surveycore/models}"
MODEL_DIR="$CACHE_DIR/$MODEL_DIR_NAME"

REQUIRED_FILES=(
    "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
    "decoder-epoch-99-avg-1-chunk-16-left-128.onnx"
    "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
    "tokens.txt"
)

if ! command -v curl >/dev/null 2>&1; then
    echo "ERROR: curl is required." >&2
    exit 1
fi

mkdir -p "$MODEL_DIR"

for name in "${REQUIRED_FILES[@]}"; do
    target="$MODEL_DIR/$name"

    if [ -s "$target" ]; then
        echo "Already ready: $target"
        continue
    fi

    temp="$target.part"
    rm -f "$temp"

    echo "Downloading $name"
    curl \
        -fL \
        --retry 5 \
        --retry-delay 2 \
        --connect-timeout 20 \
        -o "$temp" \
        "$MODEL_REPO/$name?download=true"

    mv -f "$temp" "$target"
done

for name in "${REQUIRED_FILES[@]}"; do
    if [ ! -s "$MODEL_DIR/$name" ]; then
        echo "ERROR: model file missing after download: $MODEL_DIR/$name" >&2
        exit 1
    fi
done

echo "Sherpa Better English streaming model ready: $MODEL_DIR"
for name in "${REQUIRED_FILES[@]}"; do
    ls -lh "$MODEL_DIR/$name"
done
