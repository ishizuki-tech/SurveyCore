#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

EXPECTED_CERT_SHA256="e0f3ce7dd68625e496628df2a3798d05e671968ca83a8801bcf7bd5f4c06e8ec"
EXPECTED_WHISPER_SHA1="137c40403d78fd54d454da0f9bd998f78703390c"

TOKEN_ASSET="app/src/main/assets/internal/hf_token.properties"
TOKEN_RESOURCE="app/src/main/res/values/internal_hf_token_generated.xml"

cleanup() {
  unset SURVEYCORE_HF_TOKEN || true
  unset HF_TOKEN_FOR_VERIFICATION || true

  rm -f \
    "$TOKEN_ASSET" \
    "$TOKEN_RESOURCE"
}

trap cleanup EXIT

echo "Fixed internal Release signing will be verified by Gradle during assembleRelease."

if [[ -z "${SURVEYCORE_HF_TOKEN:-}" ]]; then
  printf "Hugging Face read token: "
  IFS= read -r -s SURVEYCORE_HF_TOKEN
  printf "\n"
  export SURVEYCORE_HF_TOKEN
fi

if [[ "$SURVEYCORE_HF_TOKEN" != hf_* ]]; then
  echo "ERROR: Token does not look like a Hugging Face token." >&2
  exit 1
fi

HF_TOKEN_FOR_VERIFICATION="$SURVEYCORE_HF_TOKEN"

echo "Preparing encrypted internal Hugging Face token..."
./scripts/prepare_internal_hf_token.sh

test -s "$TOKEN_ASSET"
test -s "$TOKEN_RESOURCE"

if grep -Fq -- \
  "$SURVEYCORE_HF_TOKEN" \
  "$TOKEN_ASSET" \
  "$TOKEN_RESOURCE"
then
  echo "ERROR: plaintext Hugging Face token was written to generated files." >&2
  exit 1
fi

# Public model downloads must not inherit the gated-model credential.
unset SURVEYCORE_HF_TOKEN

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

echo "Building internal Release APK..."

rm -f \
  app/build/outputs/apk/release/*.apk \
  2>/dev/null \
  || true

./gradlew :app:assembleRelease

APK="app/build/outputs/apk/release/app-release.apk"

if [[ ! -s "$APK" ]]; then
  echo "ERROR: signed Release APK not found: $APK" >&2
  find app/build/outputs/apk/release \
    -maxdepth 1 \
    -type f \
    -print \
    2>/dev/null \
    || true
  exit 1
fi

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
APKSIGNER="$ANDROID_SDK/build-tools/37.0.0/apksigner"

if [[ ! -x "$APKSIGNER" ]]; then
  echo "ERROR: apksigner not found: $APKSIGNER" >&2
  exit 1
fi

echo
echo "Verifying fixed APK signing certificate..."

SIGNATURE_OUTPUT="$(
  "$APKSIGNER" \
    verify \
    --verbose \
    --print-certs \
    "$APK"
)"

printf '%s\n' "$SIGNATURE_OUTPUT"

SIGNER_COUNT="$(
  printf '%s\n' "$SIGNATURE_OUTPUT" \
    | awk -F': ' '/^Number of signers:/ {print $2; exit}'
)"

CERT_SHA256="$(
  printf '%s\n' "$SIGNATURE_OUTPUT" \
    | awk -F': ' '
        /certificate SHA-256 digest:/ {
          print tolower($NF)
          exit
        }
      '
)"

if [[ "$SIGNER_COUNT" != "1" ]]; then
  echo "ERROR: expected exactly one APK signer; found: ${SIGNER_COUNT:-unknown}" >&2
  exit 1
fi

if [[ "$CERT_SHA256" != "$EXPECTED_CERT_SHA256" ]]; then
  echo "ERROR: APK signing certificate mismatch." >&2
  echo "  expected: $EXPECTED_CERT_SHA256" >&2
  echo "  actual:   ${CERT_SHA256:-missing}" >&2
  exit 1
fi

echo "Signing certificate verified."

echo
echo "Verifying bundled APK contents..."

SURVEYCORE_VERIFY_HF_TOKEN="$HF_TOKEN_FOR_VERIFICATION" \
EXPECTED_WHISPER_SHA1="$EXPECTED_WHISPER_SHA1" \
python3 - "$APK" <<'PY'
import hashlib
import os
import sys
import zipfile

apk = sys.argv[1]
expected_whisper_sha1 = os.environ["EXPECTED_WHISPER_SHA1"]

model = (
    "sherpa-onnx-nemotron-speech-streaming-en-0.6b-"
    "1120ms-int8-2026-04-25"
)

nemotron_files = [
    "encoder.int8.onnx",
    "decoder.int8.onnx",
    "joiner.int8.onnx",
    "tokens.txt",
]

with zipfile.ZipFile(apk) as archive:
    for name in nemotron_files:
        path = f"assets/models/{model}/{name}"
        info = archive.getinfo(path)

        if info.file_size <= 0:
            raise SystemExit(f"Empty APK asset: {path}")

        if info.compress_type != zipfile.ZIP_STORED:
            raise SystemExit(
                f"Nemotron asset must be stored uncompressed: {path}"
            )

        print(
            f"OK {path} "
            f"{info.file_size / 1024 / 1024:.1f} MiB"
        )

    whisper_path = "assets/models/whisper/ggml-base.en.bin"
    whisper_info = archive.getinfo(whisper_path)

    if whisper_info.file_size <= 0:
        raise SystemExit("Bundled Whisper model is empty.")

    if whisper_info.compress_type != zipfile.ZIP_STORED:
        raise SystemExit(
            "Whisper model must be stored uncompressed."
        )

    whisper_sha1 = hashlib.sha1()

    with archive.open(whisper_path) as source:
        while True:
            chunk = source.read(4 * 1024 * 1024)

            if not chunk:
                break

            whisper_sha1.update(chunk)

    actual_whisper_sha1 = whisper_sha1.hexdigest()

    if actual_whisper_sha1 != expected_whisper_sha1:
        raise SystemExit(
            "Bundled Whisper model SHA-1 mismatch: "
            f"{actual_whisper_sha1}"
        )

    print(
        f"OK {whisper_path} "
        f"{whisper_info.file_size / 1024 / 1024:.1f} MiB "
        f"sha1={actual_whisper_sha1}"
    )

    token_path = "assets/internal/hf_token.properties"
    token_info = archive.getinfo(token_path)

    if token_info.file_size <= 0:
        raise SystemExit(
            "Encrypted Hugging Face token asset is empty."
        )

    token_asset = archive.read(token_path)

    for marker in (
        b"version=1",
        b"key_a=",
        b"nonce=",
        b"ciphertext=",
    ):
        if marker not in token_asset:
            raise SystemExit(
                f"Encrypted Hugging Face token marker is missing: {marker!r}"
            )

    print(f"OK {token_path}")

token = os.environ.get(
    "SURVEYCORE_VERIFY_HF_TOKEN",
    "",
).encode("utf-8")

if not token:
    raise SystemExit(
        "Hugging Face token is unavailable during APK verification."
    )

overlap = max(len(token) - 1, 0)
previous = b""

with open(apk, "rb") as source:
    while True:
        chunk = source.read(4 * 1024 * 1024)

        if not chunk:
            break

        combined = previous + chunk

        if token in combined:
            raise SystemExit(
                "Plaintext Hugging Face token was found inside the APK."
            )

        previous = (
            combined[-overlap:]
            if overlap
            else b""
        )

print("OK plaintext Hugging Face token is not present in APK")
print("APK verification passed.")
PY

echo
ls -lh "$APK"
echo
echo "Internal Release APK ready:"
echo "  $APK"
echo "Certificate SHA-256:"
echo "  $CERT_SHA256"
