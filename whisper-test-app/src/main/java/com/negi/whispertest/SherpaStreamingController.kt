package com.negi.whispertest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * True streaming English ASR using sherpa-onnx OnlineRecognizer.
 *
 * Audio is fed to the recognizer in 100 ms chunks. Unlike the Whisper live
 * experiment, the recognizer keeps decoder state between chunks and does not
 * re-run inference over a growing or rolling waveform window.
 */
internal class SherpaStreamingController(
    private val context: Context,
    private val modelDir: File,
    private val modelSpec: ModelSpec,
    listener: Listener? = null,
    private val sampleRateHz: Int = SAMPLE_RATE_HZ,
    val audioChunkMs: Int = AUDIO_CHUNK_MS,
    val threadCount: Int = DEFAULT_THREAD_COUNT,
    val endpointTrailingSilenceSeconds: Float =
        DEFAULT_ENDPOINT_TRAILING_SILENCE_SECONDS,
) : AutoCloseable {

    data class ModelSpec(
        val encoderFile: String,
        val decoderFile: String,
        val joinerFile: String,
        val tokensFile: String = "tokens.txt",
        val modelType: String = "",
    ) {
        val requiredFiles: List<String>
            get() = listOf(encoderFile, decoderFile, joinerFile, tokensFile)
    }

    interface Listener {
        fun onStarted()

        fun onAudioLevel(
            rms: Float,
            speechThreshold: Float,
        )

        fun onPartial(
            text: String,
            firstPartialLatencyMs: Long?,
        )

        fun onFinal(text: String)

        fun onError(throwable: Throwable)

        fun onStopped()
    }

    private val stopRequested = AtomicBoolean(false)
    private val runtimeLock = Any()

    @Volatile
    private var listener: Listener = listener ?: NO_OP_LISTENER

    @Volatile
    private var workerThread: Thread? = null

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var recognizer: OnlineRecognizer? = null

    @Volatile
    private var runtimeWarmedUp: Boolean = false

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    fun preload() {
        validateModelFiles()

        synchronized(runtimeLock) {
            val runtimeRecognizer =
                recognizer ?: createRecognizer().also { created ->
                    recognizer = created
                }

            if (!runtimeWarmedUp) {
                warmUpRecognizer(runtimeRecognizer)
                runtimeWarmedUp = true
            }
        }
    }

    val isPreloaded: Boolean
        get() = recognizer != null && runtimeWarmedUp

    fun start() {
        check(workerThread == null) {
            "Sherpa streaming recognizer is already running."
        }

        validateModelFiles()
        stopRequested.set(false)

        workerThread =
            thread(
                start = true,
                isDaemon = true,
                name = "SherpaStreamingASR",
            ) {
                runStreamingLoop()
            }
    }

    fun requestStop() {
        stopRequested.set(true)

        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
            // AudioRecord may already be stopped while the worker exits.
        }
    }

    override fun close() {
        requestStop()
        workerThread?.join(STOP_JOIN_TIMEOUT_MS)
        workerThread = null
        releaseAudioRecord()
        releaseRecognizer()
    }

    private fun runStreamingLoop() {
        var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null

        try {
            checkMicrophonePermission()

            val runtimeRecognizer = getOrCreateRecognizer()

            val runtimeAudioRecord = createAudioRecord()
            audioRecord = runtimeAudioRecord

            stream = runtimeRecognizer.createStream()
            runtimeAudioRecord.startRecording()
            listener.onStarted()

            val chunkSamples = sampleRateHz * audioChunkMs / 1000
            val pcm16 = ShortArray(chunkSamples)

            var lastPartialText = ""
            var speechStartedAtNs: Long? = null
            var firstPartialReported = false

            while (!stopRequested.get()) {
                val count =
                    runtimeAudioRecord.read(
                        pcm16,
                        0,
                        pcm16.size,
                        AudioRecord.READ_BLOCKING,
                    )

                if (count < 0) {
                    throw IllegalStateException("AudioRecord.read failed with code $count")
                }

                if (count == 0) {
                    continue
                }

                val samples =
                    FloatArray(count) { index ->
                        pcm16[index] / 32768.0f
                    }

                val rms = calculateRms(samples)
                listener.onAudioLevel(
                    rms = rms,
                    speechThreshold = SPEECH_RMS_THRESHOLD,
                )

                if (speechStartedAtNs == null && rms >= SPEECH_RMS_THRESHOLD) {
                    speechStartedAtNs = System.nanoTime()
                    firstPartialReported = false
                }

                stream.acceptWaveform(samples, sampleRate = sampleRateHz)

                while (runtimeRecognizer.isReady(stream)) {
                    runtimeRecognizer.decode(stream)
                }

                val result = runtimeRecognizer.getResult(stream)
                val text = result.text.trim()

                if (text.isNotEmpty() && text != lastPartialText) {
                    val latencyMs =
                        if (!firstPartialReported) {
                            speechStartedAtNs?.let { startedAt ->
                                ((System.nanoTime() - startedAt) / 1_000_000L)
                            }
                        } else {
                            null
                        }

                    listener.onPartial(
                        text = text,
                        firstPartialLatencyMs = latencyMs,
                    )

                    if (latencyMs != null) {
                        firstPartialReported = true
                    }

                    lastPartialText = text
                }

                if (runtimeRecognizer.isEndpoint(stream)) {
                    if (text.isNotEmpty()) {
                        listener.onFinal(text)
                    }

                    runtimeRecognizer.reset(stream)
                    lastPartialText = ""
                    speechStartedAtNs = null
                    firstPartialReported = false
                }
            }

            // Flush the final decoder state so stopping does not lose the last
            // unfinished word or phrase.
            stream.inputFinished()
            while (runtimeRecognizer.isReady(stream)) {
                runtimeRecognizer.decode(stream)
            }

            val finalText = runtimeRecognizer.getResult(stream).text.trim()
            if (finalText.isNotEmpty()) {
                listener.onFinal(finalText)
            }
        } catch (throwable: Throwable) {
            if (!stopRequested.get()) {
                listener.onError(throwable)
            }
        } finally {
            try {
                stream?.release()
            } catch (_: Throwable) {
                // Best-effort native cleanup while the activity is closing.
            }

            releaseAudioRecord()
            workerThread = null
            listener.onStopped()
        }
    }

    private fun warmUpRecognizer(runtimeRecognizer: OnlineRecognizer) {
        val stream = runtimeRecognizer.createStream()

        try {
            val warmUpSamples =
                FloatArray(sampleRateHz * WARM_UP_AUDIO_MS / 1000)

            stream.acceptWaveform(
                warmUpSamples,
                sampleRate = sampleRateHz,
            )
            stream.inputFinished()

            while (runtimeRecognizer.isReady(stream)) {
                runtimeRecognizer.decode(stream)
            }

            // Force result materialization so the first real microphone session
            // does not pay the initial decoder/result setup cost.
            runtimeRecognizer.getResult(stream)
        } finally {
            stream.release()
        }
    }

    private fun getOrCreateRecognizer(): OnlineRecognizer =
        synchronized(runtimeLock) {
            recognizer ?: createRecognizer().also { created ->
                recognizer = created
            }
        }

    private fun createRecognizer(): OnlineRecognizer {
        val encoder = File(modelDir, modelSpec.encoderFile)
        val decoder = File(modelDir, modelSpec.decoderFile)
        val joiner = File(modelDir, modelSpec.joinerFile)
        val tokens = File(modelDir, modelSpec.tokensFile)

        val modelConfig =
            OnlineModelConfig(
                transducer =
                    OnlineTransducerModelConfig(
                        encoder = encoder.absolutePath,
                        decoder = decoder.absolutePath,
                        joiner = joiner.absolutePath,
                    ),
                tokens = tokens.absolutePath,
                numThreads = threadCount,
                debug = false,
                provider = "cpu",
                modelType = modelSpec.modelType,
            )

        val config =
            OnlineRecognizerConfig(
                featConfig =
                    FeatureConfig(
                        sampleRate = sampleRateHz,
                        featureDim = 80,
                        dither = 0.0f,
                    ),
                modelConfig = modelConfig,
                endpointConfig =
                    EndpointConfig(
                        rule1 =
                            EndpointRule(
                                mustContainNonSilence = false,
                                minTrailingSilence = 2.4f,
                                minUtteranceLength = 0.0f,
                            ),
                        rule2 =
                            EndpointRule(
                                mustContainNonSilence = true,
                                minTrailingSilence = endpointTrailingSilenceSeconds,
                                minUtteranceLength = 0.0f,
                            ),
                        rule3 =
                            EndpointRule(
                                mustContainNonSilence = false,
                                minTrailingSilence = 0.0f,
                                minUtteranceLength = 30.0f,
                            ),
                    ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
                maxActivePaths = 4,
            )

        return OnlineRecognizer(
            assetManager = null,
            config = config,
        )
    }

    private fun createAudioRecord(): AudioRecord {
        val minBufferBytes =
            AudioRecord.getMinBufferSize(
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )

        check(minBufferBytes > 0) {
            "AudioRecord.getMinBufferSize failed: $minBufferBytes"
        }

        val chunkBytes = sampleRateHz * AUDIO_CHUNK_MS / 1000 * 2
        val bufferBytes = maxOf(minBufferBytes * 2, chunkBytes * 4)

        val recorder =
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )

        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            "AudioRecord failed to initialize."
        }

        return recorder
    }

    private fun validateModelFiles() {
        val missing =
            modelSpec.requiredFiles
                .map { File(modelDir, it) }
                .filterNot { it.isFile && it.length() > 0L }

        check(missing.isEmpty()) {
            buildString {
                append("Sherpa streaming model is incomplete: ")
                append(modelDir.absolutePath)
                append("\nMissing:\n")
                append(missing.joinToString("\n") { it.name })
            }
        }
    }

    private fun checkMicrophonePermission() {
        check(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            "RECORD_AUDIO permission is not granted."
        }
    }

    private fun releaseAudioRecord() {
        try {
            audioRecord?.release()
        } catch (_: Throwable) {
            // Best-effort cleanup.
        } finally {
            audioRecord = null
        }
    }

    private fun releaseRecognizer() {
        synchronized(runtimeLock) {
            try {
                recognizer?.release()
            } catch (_: Throwable) {
                // Best-effort native cleanup.
            } finally {
                recognizer = null
                runtimeWarmedUp = false
            }
        }
    }

    private fun calculateRms(samples: FloatArray): Float {
        if (samples.isEmpty()) {
            return 0.0f
        }

        var sumSquares = 0.0
        for (sample in samples) {
            sumSquares += sample * sample
        }

        return sqrt(sumSquares / samples.size).toFloat()
    }

    private companion object {
        val NO_OP_LISTENER =
            object : Listener {
                override fun onStarted() = Unit

                override fun onAudioLevel(
                    rms: Float,
                    speechThreshold: Float,
                ) = Unit

                override fun onPartial(
                    text: String,
                    firstPartialLatencyMs: Long?,
                ) = Unit

                override fun onFinal(text: String) = Unit

                override fun onError(throwable: Throwable) = Unit

                override fun onStopped() = Unit
            }

        const val SAMPLE_RATE_HZ = 16_000
        const val AUDIO_CHUNK_MS = 100
        const val WARM_UP_AUDIO_MS = 1_600
        const val DEFAULT_THREAD_COUNT = 4
        const val DEFAULT_ENDPOINT_TRAILING_SILENCE_SECONDS = 1.0f
        const val SPEECH_RMS_THRESHOLD = 0.010f
        const val STOP_JOIN_TIMEOUT_MS = 2_000L
    }
}
