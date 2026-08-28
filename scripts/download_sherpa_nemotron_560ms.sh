#!/usr/bin/env bash
set -euo pipefail

MODEL_DIR_NAME="sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25"
CACHE_DIR="${SURVEYCORE_MODEL_CACHE:-$HOME/.cache/surveycore/models}"
MODEL_DIR="$CACHE_DIR/$MODEL_DIR_NAME"
ARCHIVE="$CACHE_DIR/$MODEL_DIR_NAME.tar.bz2"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$MODEL_DIR_NAME.tar.bz2"

REQUIRED_FILES=(
  "encoder.int8.onnx"
  "decoder.int8.onnx"
  "joiner.int8.onnx"
  "tokens.txt"
)

mkdir -p "$CACHE_DIR"

ready=true
for name in "${REQUIRED_FILES[@]}"; do
  if [ ! -s "$MODEL_DIR/$name" ]; then
    ready=false
    break
  fi
done

if [ "$ready" = true ]; then
  echo "Nemotron 560 ms model already ready: $MODEL_DIR"
else
  echo "Downloading Nemotron 560 ms streaming model..."
  rm -f "$ARCHIVE"

  curl -fL     --retry 5     --retry-delay 2     --connect-timeout 20     -o "$ARCHIVE"     "$URL"

  tar -xjf "$ARCHIVE" -C "$CACHE_DIR"
  rm -f "$ARCHIVE"
fi

for name in "${REQUIRED_FILES[@]}"; do
  if [ ! -s "$MODEL_DIR/$name" ]; then
    echo "ERROR: missing model file: $MODEL_DIR/$name" >&2
    exit 1
  fi
done

echo "Nemotron 560 ms model ready:"
for name in "${REQUIRED_FILES[@]}"; do
  ls -lh "$MODEL_DIR/$name"
done
