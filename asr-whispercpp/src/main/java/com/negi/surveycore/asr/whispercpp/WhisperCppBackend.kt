package com.negi.surveycore.asr.whispercpp

import com.negi.surveycore.asr.Pcm16WavDecoder
import com.negi.surveycore.asr.SpeechRecognitionBackend
import com.negi.surveycore.asr.TranscriptionResult
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * English-only speech-recognition backend backed by whisper.cpp.
 *
 * The first integration milestone intentionally keeps the runtime contract
 * narrow:
 *
 * - English transcription only
 * - 16 kHz mono floating-point PCM
 * - one persistent model/context instance
 * - serialized inference on that context
 */
class WhisperCppBackend(
    private val modelPath: String,
    private val threadCount: Int = DEFAULT_THREAD_COUNT,
) : SpeechRecognitionBackend {

    override val backendId: String =
        "whisper.cpp"

    override val requiredSampleRateHz: Int =
        Pcm16WavDecoder.REQUIRED_SAMPLE_RATE_HZ

    private val lifecycleLock =
        ReentrantLock()

    @Volatile
    private var nativeHandle: Long =
        0L

    @Volatile
    private var closed: Boolean =
        false

    override fun isReady(): Boolean =
        !closed &&
                nativeHandle != 0L

    override suspend fun initialize() {
        withContext(
            Dispatchers.IO
        ) {
            lifecycleLock.withLock {
                check(!closed) {
                    "WhisperCppBackend has already been closed."
                }

                if (
                    nativeHandle != 0L
                ) {
                    return@withLock
                }

                val modelFile =
                    File(
                        modelPath
                    )

                check(
                    modelFile.isFile
                ) {
                    "Whisper model not found: ${modelFile.absolutePath}"
                }

                check(
                    modelFile.length() > 0L
                ) {
                    "Whisper model is empty: ${modelFile.absolutePath}"
                }

                val handle =
                    WhisperCppNative.create(
                        modelPath =
                            modelFile.absolutePath,
                    )

                check(
                    handle != 0L
                ) {
                    "whisper.cpp returned an invalid native handle."
                }

                nativeHandle =
                    handle
            }
        }
    }

    override suspend fun transcribe(
        samples: FloatArray,
        sampleRateHz: Int,
    ): TranscriptionResult {
        require(
            sampleRateHz == requiredSampleRateHz
        ) {
            "WhisperCppBackend requires ${requiredSampleRateHz} Hz PCM; " +
                    "received $sampleRateHz Hz."
        }

        require(
            samples.isNotEmpty()
        ) {
            "PCM sample buffer is empty."
        }

        initialize()

        check(!closed) {
            "WhisperCppBackend has already been closed."
        }

        return try {
            withContext(
                Dispatchers.IO
            ) {
                lifecycleLock.withLock {
                    val handle =
                        nativeHandle

                    check(
                        handle != 0L
                    ) {
                        "WhisperCppBackend is not initialized."
                    }

                    val inferenceStart =
                        System.nanoTime()

                    val text =
                        WhisperCppNative.transcribe(
                            handle =
                                handle,
                            samples =
                                samples,
                            sampleRateHz =
                                sampleRateHz,
                            threadCount =
                                threadCount.coerceAtLeast(1),
                        )

                    val inferenceDurationMs =
                        elapsedMillis(
                            inferenceStart
                        )

                    val audioDurationMs =
                        samples.size.toLong() *
                                1000L /
                                sampleRateHz.toLong()

                    TranscriptionResult(
                        text =
                            text.trim(),
                        audioDurationMs =
                            audioDurationMs,
                        inferenceDurationMs =
                            inferenceDurationMs,
                    )
                }
            }
        } catch (
            cancellation: CancellationException
        ) {
            throw cancellation
        }
    }

    override fun close() {
        lifecycleLock.withLock {
            if (
                closed
            ) {
                return
            }

            closed =
                true

            val handle =
                nativeHandle

            nativeHandle =
                0L

            if (
                handle != 0L
            ) {
                WhisperCppNative.close(
                    handle
                )
            }
        }
    }

    companion object {

        const val DEFAULT_THREAD_COUNT =
            4

        fun nativeInfo(): String =
            WhisperCppNative.nativeInfo()

        private fun elapsedMillis(
            startNanoseconds: Long,
        ): Long =
            (
                    System.nanoTime() -
                            startNanoseconds
                    ) /
                    1_000_000L
    }
}
