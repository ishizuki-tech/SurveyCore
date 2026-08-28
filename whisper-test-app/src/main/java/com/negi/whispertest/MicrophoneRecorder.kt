package com.negi.whispertest

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.max

/**
 * Minimal 16 kHz mono PCM recorder for the standalone Whisper test app.
 */
class MicrophoneRecorder(
    private val sampleRateHz: Int = REQUIRED_SAMPLE_RATE_HZ,
) {

    data class Recording(
        val samples: FloatArray,
        val sampleRateHz: Int,
    )

    @Volatile
    private var stopRequested: Boolean = false

    @Volatile
    private var audioRecord: AudioRecord? = null

    fun requestStop() {
        stopRequested = true

        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
            // The recorder may already be stopping.
        }
    }

    fun recordBlocking(
        maxDurationSeconds: Int = DEFAULT_MAX_DURATION_SECONDS,
    ): Recording {
        check(audioRecord == null) {
            "A microphone recording is already active."
        }

        stopRequested = false

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val minimumBufferBytes =
            AudioRecord.getMinBufferSize(
                sampleRateHz,
                channelConfig,
                encoding,
            )

        check(minimumBufferBytes > 0) {
            "AudioRecord does not support ${sampleRateHz} Hz mono PCM16. " +
                "getMinBufferSize=$minimumBufferBytes"
        }

        val bufferSizeBytes =
            max(
                minimumBufferBytes,
                sampleRateHz * BYTES_PER_SAMPLE / 5,
            )

        val recorder =
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSizeBytes)
                .build()

        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            "AudioRecord failed to initialize."
        }

        audioRecord = recorder

        val readBuffer = ShortArray(bufferSizeBytes / BYTES_PER_SAMPLE)
        val sampleBuffer = FloatSampleBuffer(sampleRateHz * 5)
        val maxSamples = sampleRateHz * maxDurationSeconds.coerceAtLeast(1)

        try {
            recorder.startRecording()

            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "AudioRecord did not enter RECORDSTATE_RECORDING."
            }

            while (!stopRequested && sampleBuffer.size < maxSamples) {
                val remainingSamples = maxSamples - sampleBuffer.size
                val requestedSamples = minOf(readBuffer.size, remainingSamples)

                val readCount =
                    recorder.read(
                        readBuffer,
                        0,
                        requestedSamples,
                        AudioRecord.READ_BLOCKING,
                    )

                when {
                    readCount > 0 -> sampleBuffer.appendPcm16(readBuffer, readCount)
                    readCount == 0 -> Unit
                    stopRequested -> break
                    else -> error("AudioRecord.read failed with code $readCount.")
                }
            }
        } finally {
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            } catch (_: IllegalStateException) {
                // Ignore a stop race while shutting down.
            }

            recorder.release()
            audioRecord = null
        }

        val samples = sampleBuffer.toFloatArray()

        check(samples.isNotEmpty()) {
            "No microphone PCM samples were captured."
        }

        return Recording(
            samples = samples,
            sampleRateHz = sampleRateHz,
        )
    }

    private class FloatSampleBuffer(
        initialCapacity: Int,
    ) {
        private var values = FloatArray(initialCapacity.coerceAtLeast(1))

        var size: Int = 0
            private set

        fun appendPcm16(
            input: ShortArray,
            count: Int,
        ) {
            ensureCapacity(size + count)

            for (index in 0 until count) {
                values[size + index] = input[index].toFloat() / PCM_16_SCALE
            }

            size += count
        }

        fun toFloatArray(): FloatArray = values.copyOf(size)

        private fun ensureCapacity(requiredCapacity: Int) {
            if (requiredCapacity <= values.size) {
                return
            }

            var newCapacity = values.size
            while (newCapacity < requiredCapacity) {
                newCapacity *= 2
            }

            values = values.copyOf(newCapacity)
        }
    }

    companion object {
        const val REQUIRED_SAMPLE_RATE_HZ = 16_000
        const val DEFAULT_MAX_DURATION_SECONDS = 30

        private const val BYTES_PER_SAMPLE = 2
        private const val PCM_16_SCALE = 32_768.0f
    }
}
