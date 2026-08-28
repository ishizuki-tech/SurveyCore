package com.negi.whispertest

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.negi.surveycore.asr.TranscriptionResult
import com.negi.surveycore.asr.whispercpp.WhisperCppBackend
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Near-real-time microphone transcription for the standalone Whisper test app.
 *
 * Audio capture never waits for Whisper inference. A small VAD watches 20 ms
 * microphone frames, starts an utterance automatically when speech appears,
 * publishes coalesced partial requests while speech continues, and publishes a
 * final request after trailing silence.
 *
 * whisper.cpp itself is still invoked on finite PCM windows. This controller
 * supplies the streaming behavior around the existing WhisperCppBackend.
 */
class LiveTranscriptionController(
    private val partialBackend: WhisperCppBackend,
    private val finalBackend: WhisperCppBackend,
    private val scope: CoroutineScope,
    private val listener: Listener,
    private val sampleRateHz: Int = REQUIRED_SAMPLE_RATE_HZ,
) {

    interface Listener {
        fun onListening()

        fun onVoiceLevel(
            rms: Float,
            threshold: Float,
            speechActive: Boolean,
        )

        fun onSpeechStarted(utteranceId: Long)

        fun onSpeechEnded(utteranceId: Long)

        fun onPartial(
            utteranceId: Long,
            result: TranscriptionResult,
        )

        fun onFinal(
            utteranceId: Long,
            result: TranscriptionResult,
        )

        fun onError(throwable: Throwable)

        fun onStopped()
    }

    private data class InferenceRequest(
        val utteranceId: Long,
        val isFinal: Boolean,
        val samples: FloatArray,
    )

    private val queueLock = Any()
    private val wakeInference = Channel<Unit>(Channel.CONFLATED)
    private val finalRequests = ArrayDeque<InferenceRequest>()

    private var latestPartialRequest: InferenceRequest? = null
    private var captureEnded = false

    @Volatile
    private var running = false

    @Volatile
    private var audioRecord: AudioRecord? = null

    private var captureJob: Job? = null
    private var inferenceJob: Job? = null

    fun start() {
        check(!running) {
            "Live transcription is already running."
        }

        running = true
        captureEnded = false

        inferenceJob =
            scope.launch(Dispatchers.IO) {
                inferenceLoop()
            }

        captureJob =
            scope.launch(Dispatchers.IO) {
                captureLoop()
            }
    }

    fun requestStop() {
        running = false

        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
            // AudioRecord may already be stopping.
        }
    }

    fun close() {
        running = false

        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
            // AudioRecord may already be stopping.
        }

        captureJob?.cancel()
        inferenceJob?.cancel()
        captureJob = null
        inferenceJob = null
        wakeInference.close()
    }

    private suspend fun captureLoop() {
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
                sampleRateHz * BYTES_PER_SAMPLE / 2,
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

        val frameSamples = sampleRateHz * FRAME_DURATION_MS / 1000
        val readBuffer = ShortArray(frameSamples)
        val floatFrame = FloatArray(frameSamples)
        val preRoll = FloatRingBuffer(sampleRateHz * PRE_ROLL_MS / 1000)
        val utterance = FloatSampleBuffer(sampleRateHz * 5)

        val endSilenceSamples = sampleRateHz * END_SILENCE_MS / 1000
        val keepTrailingSilenceSamples = sampleRateHz * KEEP_TRAILING_SILENCE_MS / 1000
        val minimumPartialSamples = sampleRateHz * MINIMUM_PARTIAL_MS / 1000
        val partialStepSamples = sampleRateHz * PARTIAL_STEP_MS / 1000
        val maximumUtteranceSamples = sampleRateHz * MAXIMUM_UTTERANCE_SECONDS
        val minimumFinalSamples = sampleRateHz * MINIMUM_FINAL_MS / 1000
        val levelReportSamples = sampleRateHz * LEVEL_REPORT_MS / 1000

        var noiseFloor = INITIAL_NOISE_FLOOR
        var speechActive = false
        var startCandidateFrames = 0
        var silenceSamples = 0
        var samplesSinceLevelReport = 0
        var lastPartialScheduledAt = 0
        var utteranceId = 0L

        try {
            recorder.startRecording()

            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "AudioRecord did not enter RECORDSTATE_RECORDING."
            }

            listener.onListening()

            while (running && scope.isActive) {
                val readCount =
                    recorder.read(
                        readBuffer,
                        0,
                        readBuffer.size,
                        AudioRecord.READ_BLOCKING,
                    )

                if (readCount <= 0) {
                    if (!running) {
                        break
                    }

                    if (readCount == 0) {
                        continue
                    }

                    error("AudioRecord.read failed with code $readCount.")
                }

                convertPcm16ToFloat(
                    input = readBuffer,
                    output = floatFrame,
                    count = readCount,
                )

                val rms = calculateRms(floatFrame, readCount)
                val startThreshold = max(MINIMUM_RMS_THRESHOLD, noiseFloor * START_THRESHOLD_MULTIPLIER)
                val continueThreshold = max(MINIMUM_RMS_THRESHOLD * 0.70f, noiseFloor * CONTINUE_THRESHOLD_MULTIPLIER)

                preRoll.append(floatFrame, readCount)
                samplesSinceLevelReport += readCount

                if (samplesSinceLevelReport >= levelReportSamples) {
                    samplesSinceLevelReport = 0
                    listener.onVoiceLevel(
                        rms = rms,
                        threshold = if (speechActive) continueThreshold else startThreshold,
                        speechActive = speechActive,
                    )
                }

                if (!speechActive) {
                    noiseFloor =
                        if (rms < startThreshold) {
                            noiseFloor * NOISE_FLOOR_DECAY + rms * (1.0f - NOISE_FLOOR_DECAY)
                        } else {
                            noiseFloor
                        }

                    if (rms >= startThreshold) {
                        startCandidateFrames += 1
                    } else {
                        startCandidateFrames = 0
                    }

                    if (startCandidateFrames >= SPEECH_START_FRAMES) {
                        utteranceId += 1
                        speechActive = true
                        startCandidateFrames = 0
                        silenceSamples = 0
                        lastPartialScheduledAt = 0
                        utterance.clear()
                        utterance.append(preRoll.toFloatArray())
                        listener.onSpeechStarted(utteranceId)
                    }

                    continue
                }

                utterance.append(floatFrame, readCount)

                if (rms >= continueThreshold) {
                    silenceSamples = 0
                } else {
                    silenceSamples += readCount
                }

                if (
                    utterance.size >= minimumPartialSamples &&
                    (
                        lastPartialScheduledAt == 0 ||
                            utterance.size - lastPartialScheduledAt >= partialStepSamples
                    )
                ) {
                    lastPartialScheduledAt = utterance.size
                    val partialWindowSamples =
                        sampleRateHz * PARTIAL_WINDOW_MS / 1000
                    val partialStartSample =
                        (utterance.size - partialWindowSamples).coerceAtLeast(0)

                    enqueuePartial(
                        utteranceId = utteranceId,
                        samples =
                            utterance.toFloatArray(
                                startIndex = partialStartSample,
                                endIndex = utterance.size,
                            ),
                    )
                }

                val endedBySilence = silenceSamples >= endSilenceSamples
                val endedByLength = utterance.size >= maximumUtteranceSamples

                if (endedBySilence || endedByLength) {
                    val trimSamples =
                        if (endedBySilence) {
                            (silenceSamples - keepTrailingSilenceSamples).coerceAtLeast(0)
                        } else {
                            0
                        }

                    val finalSampleCount =
                        (utterance.size - trimSamples).coerceAtLeast(0)

                    if (finalSampleCount >= minimumFinalSamples) {
                        enqueueFinal(
                            utteranceId = utteranceId,
                            samples = utterance.toFloatArray(finalSampleCount),
                        )
                    }

                    listener.onSpeechEnded(utteranceId)

                    speechActive = false
                    silenceSamples = 0
                    lastPartialScheduledAt = 0
                    utterance.clear()
                    preRoll.clear()
                }
            }
        } catch (throwable: Throwable) {
            if (running) {
                listener.onError(throwable)
            }
        } finally {
            if (speechActive && utterance.size >= minimumFinalSamples) {
                enqueueFinal(
                    utteranceId = utteranceId,
                    samples = utterance.toFloatArray(),
                )
                listener.onSpeechEnded(utteranceId)
            }

            running = false

            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            } catch (_: IllegalStateException) {
                // Ignore a stop race while shutting down.
            }

            recorder.release()
            audioRecord = null

            synchronized(queueLock) {
                captureEnded = true
            }

            wakeInference.trySend(Unit)
        }
    }

    private suspend fun inferenceLoop() {
        while (scope.isActive) {
            val wakeResult = wakeInference.receiveCatching()
            if (wakeResult.isClosed) {
                return
            }

            while (scope.isActive) {
                val request = nextInferenceRequest()

                if (request == null) {
                    if (isCaptureFinishedAndQueueEmpty()) {
                        listener.onStopped()
                        return
                    }

                    break
                }

                try {
                    val inferenceBackend =
                        if (request.isFinal) {
                            finalBackend
                        } else {
                            partialBackend
                        }

                    val result =
                        inferenceBackend.transcribe(
                            samples = request.samples,
                            sampleRateHz = sampleRateHz,
                        )

                    if (request.isFinal) {
                        listener.onFinal(
                            utteranceId = request.utteranceId,
                            result = result,
                        )
                    } else {
                        listener.onPartial(
                            utteranceId = request.utteranceId,
                            result = result,
                        )
                    }
                } catch (throwable: Throwable) {
                    listener.onError(throwable)
                }
            }
        }
    }

    private fun enqueuePartial(
        utteranceId: Long,
        samples: FloatArray,
    ) {
        synchronized(queueLock) {
            if (finalRequests.any { it.utteranceId == utteranceId }) {
                return
            }

            latestPartialRequest =
                InferenceRequest(
                    utteranceId = utteranceId,
                    isFinal = false,
                    samples = samples,
                )
        }

        wakeInference.trySend(Unit)
    }

    private fun enqueueFinal(
        utteranceId: Long,
        samples: FloatArray,
    ) {
        synchronized(queueLock) {
            if (latestPartialRequest?.utteranceId == utteranceId) {
                latestPartialRequest = null
            }

            finalRequests.addLast(
                InferenceRequest(
                    utteranceId = utteranceId,
                    isFinal = true,
                    samples = samples,
                )
            )
        }

        wakeInference.trySend(Unit)
    }

    private fun nextInferenceRequest(): InferenceRequest? =
        synchronized(queueLock) {
            finalRequests.pollFirst()
                ?: latestPartialRequest.also {
                    latestPartialRequest = null
                }
        }

    private fun isCaptureFinishedAndQueueEmpty(): Boolean =
        synchronized(queueLock) {
            captureEnded &&
                finalRequests.isEmpty() &&
                latestPartialRequest == null
        }

    private fun convertPcm16ToFloat(
        input: ShortArray,
        output: FloatArray,
        count: Int,
    ) {
        for (index in 0 until count) {
            output[index] = input[index].toFloat() / PCM_16_SCALE
        }
    }

    private fun calculateRms(
        samples: FloatArray,
        count: Int,
    ): Float {
        var sumSquares = 0.0

        for (index in 0 until count) {
            val sample = samples[index].toDouble()
            sumSquares += sample * sample
        }

        return sqrt(sumSquares / count.coerceAtLeast(1)).toFloat()
    }

    private class FloatSampleBuffer(
        initialCapacity: Int,
    ) {
        private var values = FloatArray(initialCapacity.coerceAtLeast(1))

        var size: Int = 0
            private set

        fun clear() {
            size = 0
        }

        fun append(
            input: FloatArray,
            count: Int = input.size,
        ) {
            require(count in 0..input.size)
            ensureCapacity(size + count)
            input.copyInto(values, destinationOffset = size, startIndex = 0, endIndex = count)
            size += count
        }

        fun toFloatArray(
            sampleCount: Int = size,
        ): FloatArray {
            require(sampleCount in 0..size)
            return values.copyOf(sampleCount)
        }

        fun toFloatArray(
            startIndex: Int,
            endIndex: Int,
        ): FloatArray {
            require(startIndex in 0..size)
            require(endIndex in startIndex..size)
            return values.copyOfRange(startIndex, endIndex)
        }

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

    private class FloatRingBuffer(
        capacity: Int,
    ) {
        private val values = FloatArray(capacity.coerceAtLeast(1))
        private var writeIndex = 0
        private var size = 0

        fun clear() {
            writeIndex = 0
            size = 0
        }

        fun append(
            input: FloatArray,
            count: Int,
        ) {
            require(count in 0..input.size)

            for (index in 0 until count) {
                values[writeIndex] = input[index]
                writeIndex = (writeIndex + 1) % values.size
                size = minOf(size + 1, values.size)
            }
        }

        fun toFloatArray(): FloatArray {
            val result = FloatArray(size)
            val startIndex = (writeIndex - size + values.size) % values.size

            for (index in 0 until size) {
                result[index] = values[(startIndex + index) % values.size]
            }

            return result
        }
    }

    companion object {
        const val REQUIRED_SAMPLE_RATE_HZ = 16_000

        private const val FRAME_DURATION_MS = 20
        private const val PRE_ROLL_MS = 300
        private const val END_SILENCE_MS = 700
        private const val KEEP_TRAILING_SILENCE_MS = 250
        private const val MINIMUM_PARTIAL_MS = 1_200
        private const val PARTIAL_STEP_MS = 1_200
        private const val PARTIAL_WINDOW_MS = 2_800
        private const val MINIMUM_FINAL_MS = 500
        private const val LEVEL_REPORT_MS = 250
        private const val MAXIMUM_UTTERANCE_SECONDS = 30

        private const val SPEECH_START_FRAMES = 3
        private const val INITIAL_NOISE_FLOOR = 0.004f
        private const val MINIMUM_RMS_THRESHOLD = 0.010f
        private const val START_THRESHOLD_MULTIPLIER = 2.8f
        private const val CONTINUE_THRESHOLD_MULTIPLIER = 1.8f
        private const val NOISE_FLOOR_DECAY = 0.97f

        private const val BYTES_PER_SAMPLE = 2
        private const val PCM_16_SCALE = 32_768.0f
    }
}
