package com.negi.surveycore.asr

import java.io.InputStream

/**
 * Minimal RIFF/WAVE decoder used by deterministic ASR smoke tests.
 *
 * The first integration milestone intentionally supports only the exact
 * format Whisper consumes without resampling:
 *
 * - RIFF/WAVE
 * - PCM integer format
 * - mono
 * - 16-bit samples
 * - 16 kHz
 *
 * Microphone capture will later bypass this decoder and provide normalized
 * FloatArray PCM directly to SpeechRecognitionBackend.
 */
object Pcm16WavDecoder {

    const val REQUIRED_SAMPLE_RATE_HZ =
        16_000

    data class DecodedAudio(
        val samples: FloatArray,
        val sampleRateHz: Int,
    )

    fun decode(
        input: InputStream,
    ): DecodedAudio {
        val bytes =
            input.use {
                it.readBytes()
            }

        require(
            bytes.size >= 12
        ) {
            "WAV file is too small."
        }

        require(
            ascii(
                bytes,
                0,
                4,
            ) == "RIFF"
        ) {
            "WAV RIFF header is missing."
        }

        require(
            ascii(
                bytes,
                8,
                4,
            ) == "WAVE"
        ) {
            "WAV WAVE header is missing."
        }

        var offset =
            12

        var audioFormat: Int? =
            null

        var channelCount: Int? =
            null

        var sampleRateHz: Int? =
            null

        var bitsPerSample: Int? =
            null

        var dataOffset: Int? =
            null

        var dataSize: Int? =
            null

        while (
            offset + 8 <= bytes.size
        ) {
            val chunkId =
                ascii(
                    bytes,
                    offset,
                    4,
                )

            val chunkSize =
                littleEndianInt32(
                    bytes,
                    offset + 4,
                )

            require(
                chunkSize >= 0
            ) {
                "WAV chunk has an invalid size: $chunkId"
            }

            val chunkDataOffset =
                offset + 8

            val chunkEnd =
                chunkDataOffset.toLong() +
                        chunkSize.toLong()

            require(
                chunkEnd <= bytes.size.toLong()
            ) {
                "WAV chunk extends beyond the file: $chunkId"
            }

            when (
                chunkId
            ) {
                "fmt " -> {
                    require(
                        chunkSize >= 16
                    ) {
                        "WAV fmt chunk is too small."
                    }

                    audioFormat =
                        littleEndianUInt16(
                            bytes,
                            chunkDataOffset,
                        )

                    channelCount =
                        littleEndianUInt16(
                            bytes,
                            chunkDataOffset + 2,
                        )

                    sampleRateHz =
                        littleEndianInt32(
                            bytes,
                            chunkDataOffset + 4,
                        )

                    bitsPerSample =
                        littleEndianUInt16(
                            bytes,
                            chunkDataOffset + 14,
                        )
                }

                "data" -> {
                    dataOffset =
                        chunkDataOffset

                    dataSize =
                        chunkSize
                }
            }

            val paddedChunkSize =
                chunkSize +
                        (chunkSize and 1)

            offset =
                chunkDataOffset +
                        paddedChunkSize
        }

        require(
            audioFormat == 1
        ) {
            "Only PCM WAV is supported; audioFormat=$audioFormat"
        }

        require(
            channelCount == 1
        ) {
            "Only mono WAV is supported; channels=$channelCount"
        }

        require(
            sampleRateHz == REQUIRED_SAMPLE_RATE_HZ
        ) {
            "Only 16 kHz WAV is supported; sampleRateHz=$sampleRateHz"
        }

        require(
            bitsPerSample == 16
        ) {
            "Only 16-bit WAV is supported; bitsPerSample=$bitsPerSample"
        }

        val pcmOffset =
            requireNotNull(
                dataOffset
            ) {
                "WAV data chunk is missing."
            }

        val pcmBytes =
            requireNotNull(
                dataSize
            ) {
                "WAV data chunk is missing."
            }

        require(
            pcmBytes % 2 == 0
        ) {
            "WAV PCM payload has an odd byte count."
        }

        val sampleCount =
            pcmBytes / 2

        val samples =
            FloatArray(
                sampleCount
            )

        var sourceOffset =
            pcmOffset

        for (
            index in 0 until sampleCount
        ) {
            val low =
                bytes[sourceOffset].toInt() and
                        0xff

            val high =
                bytes[sourceOffset + 1].toInt()

            val signedSample =
                (
                        (high shl 8) or
                                low
                        )
                    .toShort()
                    .toInt()

            samples[index] =
                signedSample /
                        32768.0f

            sourceOffset +=
                2
        }

        return DecodedAudio(
            samples =
                samples,
            sampleRateHz =
                REQUIRED_SAMPLE_RATE_HZ,
        )
    }

    private fun ascii(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): String =
        String(
            bytes,
            offset,
            length,
            Charsets.US_ASCII,
        )

    private fun littleEndianUInt16(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        (
                bytes[offset].toInt() and
                        0xff
                ) or
                (
                        (
                                bytes[offset + 1].toInt() and
                                        0xff
                                ) shl 8
                        )

    private fun littleEndianInt32(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        (
                bytes[offset].toInt() and
                        0xff
                ) or
                (
                        (
                                bytes[offset + 1].toInt() and
                                        0xff
                                ) shl 8
                        ) or
                (
                        (
                                bytes[offset + 2].toInt() and
                                        0xff
                                ) shl 16
                        ) or
                (
                        (
                                bytes[offset + 3].toInt() and
                                        0xff
                                ) shl 24
                        )
}
