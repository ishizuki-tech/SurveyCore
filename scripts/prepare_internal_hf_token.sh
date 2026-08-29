#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

TOKEN="${SURVEYCORE_HF_TOKEN:-}"

if [[ -z "$TOKEN" ]]; then
  echo "ERROR: SURVEYCORE_HF_TOKEN is not set." >&2
  exit 1
fi

if [[ "$TOKEN" != hf_* ]]; then
  echo "ERROR: SURVEYCORE_HF_TOKEN does not look like a Hugging Face token." >&2
  exit 1
fi

ASSET_DIR="$ROOT/app/src/main/assets/internal"
RES_DIR="$ROOT/app/src/main/res/values"

ASSET_FILE="$ASSET_DIR/hf_token.properties"
RES_FILE="$RES_DIR/internal_hf_token_generated.xml"

mkdir -p "$ASSET_DIR" "$RES_DIR"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

JAVA_FILE="$TMP_DIR/EncryptHfToken.java"

cat > "$JAVA_FILE" <<'JAVA'
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class EncryptHfToken {
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                "Expected asset output and resource output paths."
            );
        }

        String token = System.getenv("SURVEYCORE_HF_TOKEN");

        if (token == null || !token.startsWith("hf_")) {
            throw new IllegalStateException(
                "SURVEYCORE_HF_TOKEN is missing or invalid."
            );
        }

        SecureRandom random = new SecureRandom();

        byte[] key = new byte[KEY_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        byte[] keyA = new byte[KEY_BYTES];
        byte[] keyB = new byte[KEY_BYTES];
        byte[] keyC = new byte[KEY_BYTES];

        random.nextBytes(key);
        random.nextBytes(nonce);
        random.nextBytes(keyA);
        random.nextBytes(keyB);

        for (int i = 0; i < KEY_BYTES; i++) {
            keyC[i] =
                (byte) (
                    key[i] ^
                    keyA[i] ^
                    keyB[i]
                );
        }

        Cipher cipher =
            Cipher.getInstance(
                "AES/GCM/NoPadding"
            );

        cipher.init(
            Cipher.ENCRYPT_MODE,
            new SecretKeySpec(
                key,
                "AES"
            ),
            new GCMParameterSpec(
                GCM_TAG_BITS,
                nonce
            )
        );

        byte[] ciphertext =
            cipher.doFinal(
                token
                    .getBytes(
                        StandardCharsets.UTF_8
                    )
            );

        Base64.Encoder encoder =
            Base64.getEncoder();

        String asset =
            "version=1\n" +
            "key_a=" +
            encoder.encodeToString(keyA) +
            "\n" +
            "nonce=" +
            encoder.encodeToString(nonce) +
            "\n" +
            "ciphertext=" +
            encoder.encodeToString(ciphertext) +
            "\n";

        String resource =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<resources>\n" +
            "    <string name=\"internal_hf_key_b\" translatable=\"false\">" +
            encoder.encodeToString(keyB) +
            "</string>\n" +
            "    <string name=\"internal_hf_key_c\" translatable=\"false\">" +
            encoder.encodeToString(keyC) +
            "</string>\n" +
            "</resources>\n";

        Files.writeString(
            Path.of(args[0]),
            asset,
            StandardCharsets.UTF_8
        );

        Files.writeString(
            Path.of(args[1]),
            resource,
            StandardCharsets.UTF_8
        );

        java.util.Arrays.fill(key, (byte) 0);
        java.util.Arrays.fill(nonce, (byte) 0);
        java.util.Arrays.fill(keyA, (byte) 0);
        java.util.Arrays.fill(keyB, (byte) 0);
        java.util.Arrays.fill(keyC, (byte) 0);
        java.util.Arrays.fill(ciphertext, (byte) 0);
    }
}
JAVA

java "$JAVA_FILE" "$ASSET_FILE" "$RES_FILE"

echo "Prepared encrypted Hugging Face token material:"
echo "  $ASSET_FILE"
echo "  $RES_FILE"
echo
echo "No plaintext token was written to the project."
