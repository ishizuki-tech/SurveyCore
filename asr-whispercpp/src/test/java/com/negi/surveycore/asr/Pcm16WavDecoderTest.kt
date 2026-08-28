package com.negi.surveycore.asr

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Pcm16WavDecoderTest {

    @Test
    fun decode_pcm16Mono16k_convertsSamplesToFloat() {
        val source =
            shortArrayOf(
                Short.MIN_VALUE,
                -16_384,
                0,
                16_384,
                Short.MAX_VALUE,
            )

        val wav =
            createPcm16MonoWav(
                samples =
                    source,
                sampleRateHz =
                    16_000,
            )

        val decoded =
            Pcm16WavDecoder.decode(
                ByteArrayInputStream(
                    wav
                )
            )

        assertEquals(
            16_000,
            decoded.sampleRateHz,
        )

        assertEquals(
            source.size,
            decoded.samples.size,
        )

        assertEquals(
            -1.0f,
            decoded.samples[0],
            0.0001f,
        )

        assertEquals(
            -0.5f,
            decoded.samples[1],
            0.0001f,
        )

        assertEquals(
            0.0f,
            decoded.samples[2],
            0.0001f,
        )

        assertEquals(
            0.5f,
            decoded.samples[3],
            0.0001f,
        )

        assertTrue(
            decoded.samples[4] > 0.999f
        )
    }

    @Test(
        expected =
            IllegalArgumentException::class,
    )
    fun decode_rejectsWrongSampleRate() {
        val wav =
            createPcm16MonoWav(
                samples =
                    shortArrayOf(
                        0,
                        1,
                    ),
                sampleRateHz =
                    8_000,
            )

        Pcm16WavDecoder.decode(
            ByteArrayInputStream(
                wav
            )
        )
    }

    private fun createPcm16MonoWav(
        samples: ShortArray,
        sampleRateHz: Int,
    ): ByteArray {
        val pcmBytes =
            samples.size *
                    2

        val output =
            ByteArrayOutputStream()

        fun writeAscii(
            text: String,
        ) {
            output.write(
                text.toByteArray(
                    Charsets.US_ASCII
                )
            )
        }

        fun writeLe16(
            value: Int,
        ) {
            output.write(
                value and
                        0xff
            )

            output.write(
                value ushr 8 and
                        0xff
            )
        }

        fun writeLe32(
            value: Int,
        ) {
            output.write(
                value and
                        0xff
            )

            output.write(
                value ushr 8 and
                        0xff
            )

            output.write(
                value ushr 16 and
                        0xff
            )

            output.write(
                value ushr 24 and
                        0xff
            )
        }

        writeAscii(
            "RIFF"
        )

        writeLe32(
            36 +
                    pcmBytes
        )

        writeAscii(
            "WAVE"
        )

        writeAscii(
            "fmt "
        )

        writeLe32(
            16
        )

        writeLe16(
            1
        )

        writeLe16(
            1
        )

        writeLe32(
            sampleRateHz
        )

        writeLe32(
            sampleRateHz *
                    2
        )

        writeLe16(
            2
        )

        writeLe16(
            16
        )

        writeAscii(
            "data"
        )

        writeLe32(
            pcmBytes
        )

        samples.forEach { sample ->
            val value =
                sample.toInt()

            writeLe16(
                value
            )
        }

        return output.toByteArray()
    }
}
