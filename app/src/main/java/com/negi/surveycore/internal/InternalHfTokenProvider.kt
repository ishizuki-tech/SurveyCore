package com.negi.surveycore.internal

import android.content.Context
import android.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts the internal Hugging Face token bundled in an internal APK.
 *
 * This prevents casual extraction of a plaintext token from the APK, but it
 * is not a hardware-backed secret: a determined reverse engineer who has the
 * APK can recover any secret the APK itself is able to decrypt.
 */
object InternalHfTokenProvider {

    fun decrypt(
        context: Context,
    ): String {
        val properties =
            Properties().apply {
                context.assets
                    .open(ASSET_PATH)
                    .use {
                            input ->

                        load(input)
                    }
            }

        check(
            properties.getProperty("version") ==
                FORMAT_VERSION
        ) {
            "Unsupported internal Hugging Face token format."
        }

        val keyA =
            decode(
                properties
                    .getProperty("key_a")
                    ?: error(
                        "Missing internal token key fragment A."
                    )
            )

        val keyB =
            decodeResource(
                context = context,
                name = KEY_B_RESOURCE,
            )

        val keyC =
            decodeResource(
                context = context,
                name = KEY_C_RESOURCE,
            )

        check(
            keyA.size ==
                AES_KEY_BYTES &&
                keyB.size ==
                AES_KEY_BYTES &&
                keyC.size ==
                AES_KEY_BYTES
        ) {
            "Invalid internal token key material."
        }

        val key =
            ByteArray(
                AES_KEY_BYTES
            ) {
                    index ->

                (
                    keyA[index].toInt() xor
                        keyB[index].toInt() xor
                        keyC[index].toInt()
                    )
                    .toByte()
            }

        val nonce =
            decode(
                properties
                    .getProperty("nonce")
                    ?: error(
                        "Missing internal token nonce."
                    )
            )

        val ciphertext =
            decode(
                properties
                    .getProperty("ciphertext")
                    ?: error(
                        "Missing internal token ciphertext."
                    )
            )

        return try {
            check(
                nonce.size ==
                    GCM_NONCE_BYTES
            ) {
                "Invalid internal token nonce."
            }

            val cipher =
                Cipher.getInstance(
                    "AES/GCM/NoPadding"
                )

            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(
                    key,
                    "AES",
                ),
                GCMParameterSpec(
                    GCM_TAG_BITS,
                    nonce,
                ),
            )

            val plaintext =
                cipher.doFinal(
                    ciphertext
                )

            val token =
                plaintext
                    .toString(
                        Charsets.UTF_8
                    )
                    .trim()

            plaintext.fill(
                0
            )

            check(
                token.startsWith(
                    "hf_"
                )
            ) {
                "Decrypted Hugging Face token is invalid."
            }

            token
        } finally {
            key.fill(0)
            keyA.fill(0)
            keyB.fill(0)
            keyC.fill(0)
            nonce.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun decodeResource(
        context: Context,
        name: String,
    ): ByteArray {
        val resourceId =
            context.resources
                .getIdentifier(
                    name,
                    "string",
                    context.packageName,
                )

        check(
            resourceId != 0
        ) {
            "Internal Hugging Face token was not prepared for this APK."
        }

        return decode(
            context.getString(
                resourceId
            )
        )
    }

    private fun decode(
        value: String,
    ): ByteArray =
        Base64.decode(
            value.trim(),
            Base64.NO_WRAP,
        )

    private const val ASSET_PATH =
        "internal/hf_token.properties"

    private const val KEY_B_RESOURCE =
        "internal_hf_key_b"

    private const val KEY_C_RESOURCE =
        "internal_hf_key_c"

    private const val FORMAT_VERSION =
        "1"

    private const val AES_KEY_BYTES =
        32

    private const val GCM_NONCE_BYTES =
        12

    private const val GCM_TAG_BITS =
        128
}
