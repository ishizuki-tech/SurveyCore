package com.negi.surveycore.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.negi.surveycore.asr.sherpa.NemotronStreamingAsr
import com.negi.surveycore.asr.whispercpp.WhisperCppBackend
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking

/** App-level ASR engines selectable from the SurveyCore UI. */
enum class VoiceAsrEngine(val displayName: String) {
    NEMOTRON_1120("Nemotron 1120ms"),
    WHISPER_BASE_EN("Whisper base.en"),
}

/** Common microphone ASR contract used by the survey UI. */
interface SelectableVoiceAsr : AutoCloseable {
    val displayName: String
    fun preload()
    fun start()
    fun requestStop()

    interface Listener {
        fun onStarted()
        fun onAudioLevel(rms: Float)
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onProcessing()
        fun onError(throwable: Throwable)
        fun onStopped()
    }
}

/** Adapter around the existing production Nemotron streaming recognizer. */
class NemotronSelectableVoiceAsr(
    context: Context,
    listener: SelectableVoiceAsr.Listener,
) : SelectableVoiceAsr {
    @Volatile
    private var hadError: Boolean =
        false

    private val delegate =
        NemotronStreamingAsr(
            context = context,
            listener =
                object : NemotronStreamingAsr.Listener {
                    override fun onStarted() {
                        hadError = false
                        listener.onStarted()
                    }
                    override fun onAudioLevel(rms: Float) = listener.onAudioLevel(rms)
                    override fun onPartial(text: String) = listener.onPartial(text)
                    override fun onFinal(text: String) = listener.onFinal(text)
                    override fun onError(
                        throwable: Throwable,
                    ) {
                        hadError = true
                        listener.onError(throwable)
                    }

                    override fun onStopped() {
                        if (!hadError) {
                            listener.onStopped()
                        }
                    }
                },
        )

    override val displayName = "Nemotron 1120ms"
    override fun preload() = delegate.preload()
    override fun start() = delegate.start()
    override fun requestStop() = delegate.requestStop()
    override fun close() = delegate.close()
}

/**
 * Stop-to-transcribe Whisper base.en microphone ASR.
 *
 * The model is bundled in the APK and copied once to private storage because
 * whisper.cpp currently loads this model by filesystem path.
 */
class WhisperSelectableVoiceAsr(
    private val context: Context,
    private val listener: SelectableVoiceAsr.Listener,
) : SelectableVoiceAsr {
    private val stopRequested = AtomicBoolean(false)
    private val runtimeLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var workerThread: Thread? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var backend: WhisperCppBackend? = null

    override val displayName = "Whisper base.en"

    override fun preload() {
        val model = ensureModelInstalled()
        synchronized(runtimeLock) {
            val runtime =
                backend ?: WhisperCppBackend(
                    modelPath = model.absolutePath,
                    threadCount = THREAD_COUNT,
                ).also { backend = it }
            runBlocking { runtime.initialize() }
        }
    }

    override fun start() {
        check(workerThread == null) { "Whisper ASR is already running." }
        checkMicrophonePermission()
        preload()
        stopRequested.set(false)
        workerThread = thread(start = true, isDaemon = true, name = "WhisperVoiceASR") {
            recordAndTranscribe()
        }
    }

    override fun requestStop() {
        stopRequested.set(true)
        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
            // Ignore shutdown races.
        }
    }

    override fun close() {
        requestStop()
        workerThread?.join(STOP_JOIN_TIMEOUT_MS)
        workerThread = null
        releaseAudioRecord()
        synchronized(runtimeLock) {
            backend?.close()
            backend = null
        }
    }

    private fun recordAndTranscribe() {
        var transcribing =
            false

        var failed =
            false

        try {
            val recorder = createAudioRecord()
            audioRecord = recorder
            val samples = FloatSampleBuffer(SAMPLE_RATE_HZ * 5)
            val readBuffer = ShortArray(SAMPLE_RATE_HZ / 5)
            val maxSamples = SAMPLE_RATE_HZ * MAX_RECORDING_SECONDS

            recorder.startRecording()
            notifyMain { onStarted() }

            while (!stopRequested.get() && samples.size < maxSamples) {
                val count = recorder.read(
                    readBuffer,
                    0,
                    minOf(readBuffer.size, maxSamples - samples.size),
                    AudioRecord.READ_BLOCKING,
                )
                when {
                    count > 0 -> {
                        samples.appendPcm16(readBuffer, count)
                        notifyMain { onAudioLevel(calculateRms(readBuffer, count)) }
                    }
                    count == 0 -> Unit
                    stopRequested.get() -> break
                    else -> error("AudioRecord.read failed with code $count.")
                }
            }

            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            } catch (_: IllegalStateException) {
                // Ignore shutdown races.
            }

            val captured = samples.toFloatArray()
            if (captured.isNotEmpty()) {
                transcribing =
                    true

                notifyMain { onProcessing() }

                val runtime = checkNotNull(backend) { "Whisper backend is unavailable." }
                val result = runBlocking {
                    runtime.transcribe(captured, SAMPLE_RATE_HZ)
                }
                if (result.text.isNotBlank()) {
                    notifyMain { onFinal(result.text) }
                }
            }
        } catch (throwable: Throwable) {
            val expectedRecorderStop =
                stopRequested.get() &&
                    !transcribing

            if (!expectedRecorderStop) {
                failed =
                    true

                notifyMain {
                    onError(
                        throwable
                    )
                }
            }
        } finally {
            releaseAudioRecord()

            workerThread =
                null

            if (!failed) {
                notifyMain {
                    onStopped()
                }
            }
        }
    }

    private fun ensureModelInstalled(): File {
        val destination = File(context.filesDir, MODEL_RELATIVE_PATH)
        if (destination.isFile && destination.length() > 0L) return destination

        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.part")
        temporary.delete()

        context.assets.open(MODEL_ASSET_PATH).use { input ->
            FileOutputStream(temporary).use { output ->
                input.copyTo(output, MODEL_COPY_BUFFER_BYTES)
                output.fd.sync()
            }
        }
        check(temporary.length() > 0L) { "Bundled Whisper model is empty." }

        if (destination.exists() && !destination.delete()) {
            error("Could not replace the Whisper model.")
        }
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
        check(destination.isFile && destination.length() > 0L) {
            "Whisper model extraction failed."
        }
        return destination
    }

    private fun createAudioRecord(): AudioRecord {
        checkMicrophonePermission()
        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBytes > 0) { "AudioRecord.getMinBufferSize failed: $minBytes" }
        val recorder = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBytes * 2, SAMPLE_RATE_HZ * 2))
                .build()
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

    private fun checkMicrophonePermission() {
        check(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) { "RECORD_AUDIO permission is not granted." }
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

    private fun calculateRms(input: ShortArray, count: Int): Float {
        if (count <= 0) return 0.0f
        var sum = 0.0
        for (i in 0 until count) {
            val value = input[i].toDouble() / PCM_16_SCALE
            sum += value * value
        }
        return sqrt(sum / count).toFloat()
    }

    private fun notifyMain(block: SelectableVoiceAsr.Listener.() -> Unit) {
        mainHandler.post { listener.block() }
    }

    private class FloatSampleBuffer(initialCapacity: Int) {
        private var values = FloatArray(initialCapacity.coerceAtLeast(1))
        var size: Int = 0
            private set

        fun appendPcm16(input: ShortArray, count: Int) {
            ensureCapacity(size + count)
            for (i in 0 until count) {
                values[size + i] = input[i].toFloat() / PCM_16_SCALE.toFloat()
            }
            size += count
        }

        fun toFloatArray(): FloatArray = values.copyOf(size)

        private fun ensureCapacity(required: Int) {
            if (required <= values.size) return
            var next = values.size
            while (next < required) next *= 2
            values = values.copyOf(next)
        }
    }

    private companion object {
        const val MODEL_RELATIVE_PATH = "models/ggml-base.en.bin"
        const val MODEL_ASSET_PATH = "models/whisper/ggml-base.en.bin"
        const val THREAD_COUNT = 4
        const val SAMPLE_RATE_HZ = 16_000
        const val MAX_RECORDING_SECONDS = 30
        const val PCM_16_SCALE = 32_768.0
        const val MODEL_COPY_BUFFER_BYTES = 1024 * 1024
        const val STOP_JOIN_TIMEOUT_MS = 5_000L
    }
}
