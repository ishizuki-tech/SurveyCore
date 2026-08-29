#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ -z "${SURVEYCORE_HF_TOKEN:-}" ]]; then
  printf "Hugging Face read token: "
  IFS= read -r -s SURVEYCORE_HF_TOKEN
  printf "\n"
  export SURVEYCORE_HF_TOKEN
fi

if [[ "$SURVEYCORE_HF_TOKEN" != hf_* ]]; then
  echo "ERROR: Token does not look like a Hugging Face token." >&2
  unset SURVEYCORE_HF_TOKEN
  exit 1
fi

echo "Preparing encrypted internal Hugging Face token..."
./scripts/prepare_internal_hf_token.sh

echo "Preparing bundled Nemotron 1120ms model..."
./scripts/download_sherpa_nemotron_1120ms.sh

MODEL_NAME="sherpa-onnx-nemotron-speech-streaming-en-0.6b-1120ms-int8-2026-04-25"
MODEL_DIR="$HOME/.cache/surveycore/models/$MODEL_NAME"
ASSET_DIR="app/src/main/assets/models/$MODEL_NAME"

mkdir -p "$ASSET_DIR"

for name in \
  encoder.int8.onnx \
  decoder.int8.onnx \
  joiner.int8.onnx \
  tokens.txt
do
  test -s "$MODEL_DIR/$name"
  cp -f "$MODEL_DIR/$name" "$ASSET_DIR/$name"
done

echo "Preparing bundled Whisper base.en model..."
./scripts/download_whisper_base_en.sh

WHISPER_MODEL="$HOME/.cache/surveycore/models/ggml-base.en.bin"
WHISPER_ASSET_DIR="app/src/main/assets/models/whisper"
mkdir -p "$WHISPER_ASSET_DIR"
test -s "$WHISPER_MODEL"
cp -f "$WHISPER_MODEL" "$WHISPER_ASSET_DIR/ggml-base.en.bin"

unset SURVEYCORE_HF_TOKEN

echo "Building internal Release APK..."
./gradlew :app:assembleRelease

APK="$(
  find app/build/outputs/apk/release \
    -maxdepth 1 \
    -type f \
    -name '*.apk' \
    -print \
    | head -1
)"

if [[ -z "$APK" ]]; then
  echo "ERROR: Release APK not found." >&2
  exit 1
fi

echo
echo "Verifying bundled files..."
python3 - "$APK" <<'PY'
import sys
import zipfile

apk = sys.argv[1]

required = [
    "assets/internal/hf_token.properties",
    "assets/models/whisper/ggml-base.en.bin",
    (
        "assets/models/"
        "sherpa-onnx-nemotron-speech-streaming-en-0.6b-1120ms-int8-2026-04-25/"
        "encoder.int8.onnx"
    ),
    (
        "assets/models/"
        "sherpa-onnx-nemotron-speech-streaming-en-0.6b-1120ms-int8-2026-04-25/"
        "decoder.int8.onnx"
    ),
    (
        "assets/models/"
        "sherpa-onnx-nemotron-speech-streaming-en-0.6b-1120ms-int8-2026-04-25/"
        "joiner.int8.onnx"
    ),
    (
        "assets/models/"
        "sherpa-onnx-nemotron-speech-streaming-en-0.6b-1120ms-int8-2026-04-25/"
        "tokens.txt"
    ),
]

with zipfile.ZipFile(apk) as archive:
    names = set(archive.namelist())

    for name in required:
        if name not in names:
            raise SystemExit(f"Missing APK entry: {name}")
        info = archive.getinfo(name)
        if info.file_size <= 0:
            raise SystemExit(f"Empty APK entry: {name}")
        print(f"OK {name}")

print("APK verification passed.")
PY

echo
ls -lh "$APK"
echo
echo "Internal Release APK ready:"
echo "  $APK"
