package com.negi.surveycore.asr.sherpa

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Production streaming ASR for SurveyCore.
 *
 * The recognizer is fixed to the selected Nemotron 0.6B 1120 ms INT8 model.
 * It is preloaded and warmed once, then kept resident while microphone
 * sessions create and release only OnlineStream and AudioRecord instances.
 *
 * Listener callbacks are always delivered on the Android main thread.
 */
class NemotronStreamingAsr(
    private val context: Context,
    private val listener: Listener,
) : AutoCloseable {

    interface Listener {
        fun onStarted()
        fun onAudioLevel(rms: Float)
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(throwable: Throwable)
        fun onStopped()
    }

    private val stopRequested = AtomicBoolean(false)
    private val runtimeLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var workerThread: Thread? = null

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var recognizer: OnlineRecognizer? = null

    @Volatile
    private var runtimeWarmedUp = false

    val isPrepared: Boolean
        get() = recognizer != null && runtimeWarmedUp

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

    fun start() {
        check(workerThread == null) {
            "Nemotron streaming ASR is already running."
        }

        validateModelFiles()
        checkMicrophonePermission()
        stopRequested.set(false)

        workerThread =
            thread(
                start = true,
                isDaemon = true,
                name = "NemotronStreamingASR",
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
            notifyMain { onStarted() }

            val chunkSamples = SAMPLE_RATE_HZ * AUDIO_CHUNK_MS / 1000
            val pcm16 = ShortArray(chunkSamples)

            var lastPartialText = ""

            while (!stopRequested.get()) {
                val count =
                    runtimeAudioRecord.read(
                        pcm16,
                        0,
                        pcm16.size,
                        AudioRecord.READ_BLOCKING,
                    )

                if (count < 0) {
                    throw IllegalStateException(
                        "AudioRecord.read failed with code $count"
                    )
                }

                if (count == 0) {
                    continue
                }

                val samples =
                    FloatArray(count) { index ->
                        pcm16[index] / 32768.0f
                    }

                notifyMain {
                    onAudioLevel(calculateRms(samples))
                }

                stream.acceptWaveform(
                    samples,
                    sampleRate = SAMPLE_RATE_HZ,
                )

                while (runtimeRecognizer.isReady(stream)) {
                    runtimeRecognizer.decode(stream)
                }

                val text =
                    runtimeRecognizer
                        .getResult(stream)
                        .text
                        .trim()

                if (text.isNotEmpty() && text != lastPartialText) {
                    notifyMain {
                        onPartial(text)
                    }
                    lastPartialText = text
                }

                if (runtimeRecognizer.isEndpoint(stream)) {
                    if (text.isNotEmpty()) {
                        notifyMain {
                            onFinal(text)
                        }
                    }

                    runtimeRecognizer.reset(stream)
                    lastPartialText = ""
                }
            }

            stream.inputFinished()

            while (runtimeRecognizer.isReady(stream)) {
                runtimeRecognizer.decode(stream)
            }

            val finalText =
                runtimeRecognizer
                    .getResult(stream)
                    .text
                    .trim()

            if (finalText.isNotEmpty()) {
                notifyMain {
                    onFinal(finalText)
                }
            }
        } catch (throwable: Throwable) {
            if (!stopRequested.get()) {
                notifyMain {
                    onError(throwable)
                }
            }
        } finally {
            try {
                stream?.release()
            } catch (_: Throwable) {
                // Best-effort native cleanup.
            }

            releaseAudioRecord()
            workerThread = null
            notifyMain {
                onStopped()
            }
        }
    }

    private fun warmUpRecognizer(
        runtimeRecognizer: OnlineRecognizer,
    ) {
        val stream = runtimeRecognizer.createStream()

        try {
            val warmUpSamples =
                FloatArray(
                    SAMPLE_RATE_HZ * WARM_UP_AUDIO_MS / 1000
                )

            stream.acceptWaveform(
                warmUpSamples,
                sampleRate = SAMPLE_RATE_HZ,
            )
            stream.inputFinished()

            while (runtimeRecognizer.isReady(stream)) {
                runtimeRecognizer.decode(stream)
            }

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
        val encoder = "$MODEL_ASSET_DIR/$ENCODER_FILE"
        val decoder = "$MODEL_ASSET_DIR/$DECODER_FILE"
        val joiner = "$MODEL_ASSET_DIR/$JOINER_FILE"
        val tokens = "$MODEL_ASSET_DIR/$TOKENS_FILE"

        val modelConfig =
            OnlineModelConfig(
                transducer =
                    OnlineTransducerModelConfig(
                        encoder = encoder,
                        decoder = decoder,
                        joiner = joiner,
                    ),
                tokens = tokens,
                numThreads = THREAD_COUNT,
                debug = false,
                provider = "cpu",
                modelType = "",
            )

        val config =
            OnlineRecognizerConfig(
                featConfig =
                    FeatureConfig(
                        sampleRate = SAMPLE_RATE_HZ,
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
                                minTrailingSilence =
                                    ENDPOINT_TRAILING_SILENCE_SECONDS,
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
            assetManager = context.assets,
            config = config,
        )
    }

    private fun createAudioRecord(): AudioRecord {
        val minBufferBytes =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )

        check(minBufferBytes > 0) {
            "AudioRecord.getMinBufferSize failed: $minBufferBytes"
        }

        val chunkBytes =
            SAMPLE_RATE_HZ * AUDIO_CHUNK_MS / 1000 * 2

        val bufferBytes =
            maxOf(
                minBufferBytes * 2,
                chunkBytes * 4,
            )

        val recorder =
            try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
            } catch (securityException: SecurityException) {
                throw IllegalStateException(
                    "RECORD_AUDIO permission is unavailable.",
                    securityException,
                )
            }

        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            "AudioRecord failed to initialize."
        }

        return recorder
    }

    private fun validateModelFiles() {
        val requiredFiles =
            listOf(
                ENCODER_FILE,
                DECODER_FILE,
                JOINER_FILE,
                TOKENS_FILE,
            )

        val missing =
            requiredFiles.filterNot { name ->
                val assetPath =
                    "$MODEL_ASSET_DIR/$name"

                try {
                    context.assets
                        .openFd(assetPath)
                        .use { descriptor ->
                            descriptor.length > 0L
                        }
                } catch (_: Exception) {
                    false
                }
            }

        check(missing.isEmpty()) {
            buildString {
                append(
                    "Bundled Nemotron 1120ms model is incomplete."
                )
                append("\nAsset directory: ")
                append(MODEL_ASSET_DIR)
                append("\nMissing or compressed assets:\n")
                append(missing.joinToString("\n"))
            }
        }
    }

    private fun checkMicrophonePermission() {
        check(
            context.checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
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

    private fun calculateRms(
        samples: FloatArray,
    ): Float {
        if (samples.isEmpty()) {
            return 0.0f
        }

        var sumSquares = 0.0

        for (sample in samples) {
            sumSquares += sample * sample
        }

        return sqrt(
            sumSquares / samples.size
        ).toFloat()
    }

    private fun notifyMain(
        block: Listener.() -> Unit,
    ) {
        mainHandler.post {
            listener.block()
        }
    }

    companion object {
        const val MODEL_ASSET_DIR =
            "models/sherpa-onnx-nemotron-speech-streaming-en-0.6b-1120ms-int8-2026-04-25"

        const val MODEL_DISPLAY_NAME =
            "Nemotron Speech Streaming English 0.6B 1120ms INT8"

        const val THREAD_COUNT = 4
        const val AUDIO_CHUNK_MS = 100
        const val ENDPOINT_TRAILING_SILENCE_SECONDS = 1.0f

        private const val SAMPLE_RATE_HZ = 16_000
        private const val WARM_UP_AUDIO_MS = 1_600
        private const val STOP_JOIN_TIMEOUT_MS = 2_000L

        private const val ENCODER_FILE = "encoder.int8.onnx"
        private const val DECODER_FILE = "decoder.int8.onnx"
        private const val JOINER_FILE = "joiner.int8.onnx"
        private const val TOKENS_FILE = "tokens.txt"
    }
}
