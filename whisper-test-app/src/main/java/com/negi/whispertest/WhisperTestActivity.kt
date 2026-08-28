package com.negi.whispertest

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.negi.surveycore.asr.Pcm16WavDecoder
import com.negi.surveycore.asr.TranscriptionResult
import com.negi.surveycore.asr.whispercpp.WhisperCppBackend
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Standalone app for testing whisper.cpp without SurveyCore or an SLM runtime.
 */
class WhisperTestActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var statusView: TextView
    private lateinit var transcriptView: TextView
    private lateinit var nativeView: TextView
    private lateinit var wavButton: Button
    private lateinit var benchmarkButton: Button
    private lateinit var recordButton: Button

    private var backend: WhisperCppBackend? = null
    private var recorder: MicrophoneRecorder? = null
    private var recordingJob: Job? = null
    private var backendReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
        initializeBackend()
    }

    override fun onDestroy() {
        recorder?.requestStop()
        recorder = null
        recordingJob?.cancel()
        recordingJob = null
        backend?.close()
        backend = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != MICROPHONE_PERMISSION_REQUEST) {
            return
        }

        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            setStatus("Microphone permission denied.")
        }
    }

    private fun buildContentView(): ScrollView {
        val padding = (24 * resources.displayMetrics.density).toInt()

        val container =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(padding, padding, padding, padding)
            }

        val titleView =
            TextView(this).apply {
                text = "Whisper Test"
                textSize = 28f
            }

        val subtitleView =
            TextView(this).apply {
                text = "Standalone English whisper.cpp ASR"
                textSize = 17f
                setPadding(0, padding / 2, 0, padding)
            }

        statusView =
            TextView(this).apply {
                text = "Initializing ggml-base.en.bin..."
                textSize = 18f
            }

        wavButton =
            Button(this).apply {
                text = "Run bundled WAV test"
                isEnabled = false
                setOnClickListener { runBundledWavTest() }
            }

        benchmarkButton =
            Button(this).apply {
                text = "Run 5x benchmark"
                isEnabled = false
                setOnClickListener { runBundledWavBenchmark() }
            }

        recordButton =
            Button(this).apply {
                text = "Start microphone"
                isEnabled = false
                setOnClickListener {
                    if (recordingJob == null) {
                        ensureMicrophonePermissionAndStart()
                    } else {
                        stopRecording()
                    }
                }
            }

        transcriptView =
            TextView(this).apply {
                text = "Transcript will appear here."
                textSize = 20f
                setPadding(0, padding, 0, padding)
            }

        nativeView =
            TextView(this).apply {
                text = "native: waiting"
                textSize = 14f
            }

        container.addView(titleView)
        container.addView(subtitleView)
        container.addView(statusView)
        container.addView(wavButton)
        container.addView(benchmarkButton)
        container.addView(recordButton)
        container.addView(transcriptView)
        container.addView(nativeView)

        return ScrollView(this).apply {
            addView(container)
        }
    }

    private fun initializeBackend() {
        scope.launch {
            val modelFile = File(filesDir, MODEL_RELATIVE_PATH)

            if (!modelFile.isFile) {
                setStatus(
                    "MODEL NOT FOUND\n${modelFile.absolutePath}\n\n" +
                        "Install the model with:\n" +
                        "./scripts/install_whisper_base_en_test_app.sh"
                )
                return@launch
            }

            try {
                val runtimeBackend =
                    WhisperCppBackend(
                        modelPath = modelFile.absolutePath,
                        threadCount = THREAD_COUNT,
                    )

                backend = runtimeBackend

                val initializeStart = System.nanoTime()
                runtimeBackend.initialize()

                backendReady = true
                wavButton.isEnabled = true
                benchmarkButton.isEnabled = true
                recordButton.isEnabled = true

                setStatus(
                    "READY\n" +
                        "backend: ${runtimeBackend.backendId}\n" +
                        "model: ${modelFile.name}\n" +
                        "initialize: ${formatSeconds(elapsedSeconds(initializeStart))} sec"
                )

                nativeView.text = "native:\n${WhisperCppBackend.nativeInfo()}"
            } catch (throwable: Throwable) {
                Log.e(LOG_TAG, "Whisper initialization failed.", throwable)
                showError("Initialization error", throwable)
            }
        }
    }

    private fun runBundledWavTest() {
        if (!backendReady || recordingJob != null) {
            return
        }

        wavButton.isEnabled = false
        benchmarkButton.isEnabled = false
        recordButton.isEnabled = false
        transcriptView.text = "Running bundled WAV..."
        setStatus("TRANSCRIBING BUNDLED WAV")

        scope.launch {
            try {
                val decoded =
                    withContext(Dispatchers.IO) {
                        assets.open(BUNDLED_WAV_ASSET).use { input ->
                            Pcm16WavDecoder.decode(input)
                        }
                    }

                val result =
                    requireNotNull(backend).transcribe(
                        samples = decoded.samples,
                        sampleRateHz = decoded.sampleRateHz,
                    )

                showResult("BUNDLED WAV READY", result)
            } catch (throwable: Throwable) {
                Log.e(LOG_TAG, "Bundled WAV test failed.", throwable)
                showError("Bundled WAV error", throwable)
            } finally {
                wavButton.isEnabled = backendReady
                benchmarkButton.isEnabled = backendReady
                recordButton.isEnabled = backendReady
            }
        }
    }

    private fun runBundledWavBenchmark() {
        if (!backendReady || recordingJob != null) {
            return
        }

        wavButton.isEnabled = false
        benchmarkButton.isEnabled = false
        recordButton.isEnabled = false
        transcriptView.text = "Benchmark running..."

        scope.launch {
            try {
                val decoded =
                    withContext(Dispatchers.IO) {
                        assets.open(BUNDLED_WAV_ASSET).use { input ->
                            Pcm16WavDecoder.decode(input)
                        }
                    }

                val results =
                    ArrayList<TranscriptionResult>(BENCHMARK_RUN_COUNT)

                repeat(BENCHMARK_RUN_COUNT) { index ->
                    setStatus(
                        "BENCHMARK RUN ${index + 1}/$BENCHMARK_RUN_COUNT\n" +
                            "audio: ${formatSeconds(decoded.samples.size.toDouble() / decoded.sampleRateHz)} sec\n" +
                            "threads: $THREAD_COUNT"
                    )

                    val result =
                        requireNotNull(backend).transcribe(
                            samples = decoded.samples,
                            sampleRateHz = decoded.sampleRateHz,
                        )

                    results += result
                }

                showBenchmarkResult(results)
            } catch (throwable: Throwable) {
                Log.e(LOG_TAG, "Bundled WAV benchmark failed.", throwable)
                showError("Benchmark error", throwable)
            } finally {
                wavButton.isEnabled = backendReady
                benchmarkButton.isEnabled = backendReady
                recordButton.isEnabled = backendReady
            }
        }
    }

    private fun showBenchmarkResult(results: List<TranscriptionResult>) {
        check(results.isNotEmpty()) {
            "Benchmark produced no results."
        }

        val inferenceTimesMs = results.map { it.inferenceDurationMs }
        val averageInferenceMs = inferenceTimesMs.average()
        val minimumInferenceMs = inferenceTimesMs.min()
        val maximumInferenceMs = inferenceTimesMs.max()
        val averageRtf = results.map { it.realtimeFactor }.average()
        val referenceTranscript = results.first().text
        val transcriptStable = results.all { it.text == referenceTranscript }

        val runLines =
            results.mapIndexed { index, result ->
                "${index + 1}: ${formatSeconds(result.inferenceDurationMs / 1000.0)} sec" +
                    "  RTF ${formatRtf(result.realtimeFactor)}"
            }

        transcriptView.text =
            buildString {
                append(referenceTranscript.ifBlank { "(No speech recognized)" })
                append("\n\nTranscript stable: ")
                append(if (transcriptStable) "YES" else "NO")
            }

        setStatus(
            buildString {
                append("BUNDLED WAV BENCHMARK READY\n")
                append("audio: ${formatSeconds(results.first().audioDurationMs / 1000.0)} sec\n")
                append("runs: ${results.size}\n")
                append("threads: $THREAD_COUNT\n\n")
                append(runLines.joinToString("\n"))
                append("\n\n")
                append("average: ${formatSeconds(averageInferenceMs / 1000.0)} sec\n")
                append("min: ${formatSeconds(minimumInferenceMs / 1000.0)} sec\n")
                append("max: ${formatSeconds(maximumInferenceMs / 1000.0)} sec\n")
                append("average RTF: ${formatRtf(averageRtf)}")
            }
        )
    }

    private fun ensureMicrophonePermissionAndStart() {
        if (!backendReady) {
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
            return
        }

        requestPermissions(
            arrayOf(Manifest.permission.RECORD_AUDIO),
            MICROPHONE_PERMISSION_REQUEST,
        )
    }

    private fun startRecording() {
        if (recordingJob != null || !backendReady) {
            return
        }

        val microphoneRecorder =
            MicrophoneRecorder(
                sampleRateHz = REQUIRED_SAMPLE_RATE_HZ,
            )

        recorder = microphoneRecorder
        wavButton.isEnabled = false
        benchmarkButton.isEnabled = false
        recordButton.text = "Stop and transcribe"
        transcriptView.text = "Listening..."
        setStatus("RECORDING\n16 kHz mono PCM16\nMaximum $MAX_RECORDING_SECONDS sec")

        val job =
            scope.launch {
                try {
                    val recording =
                        withContext(Dispatchers.IO) {
                            microphoneRecorder.recordBlocking(
                                maxDurationSeconds = MAX_RECORDING_SECONDS,
                            )
                        }

                    recorder = null
                    recordButton.text = "Start microphone"
                    recordButton.isEnabled = false

                    val result =
                        requireNotNull(backend).transcribe(
                            samples = recording.samples,
                            sampleRateHz = recording.sampleRateHz,
                        )

                    showResult("MICROPHONE READY", result)
                } catch (throwable: Throwable) {
                    Log.e(LOG_TAG, "Microphone test failed.", throwable)
                    showError("Microphone error", throwable)
                } finally {
                    recorder = null
                    recordingJob = null
                    recordButton.text = "Start microphone"
                    recordButton.isEnabled = backendReady
                    wavButton.isEnabled = backendReady
                    benchmarkButton.isEnabled = backendReady
                }
            }

        recordingJob = job
    }

    private fun stopRecording() {
        recordButton.isEnabled = false
        setStatus("Stopping microphone...")
        recorder?.requestStop()
    }

    private fun showResult(
        label: String,
        result: TranscriptionResult,
    ) {
        transcriptView.text = result.text.ifBlank { "(No speech recognized)" }
        setStatus(
            "$label\n" +
                "audio: ${formatSeconds(result.audioDurationMs / 1000.0)} sec\n" +
                "inference: ${formatSeconds(result.inferenceDurationMs / 1000.0)} sec\n" +
                "RTF: ${formatRtf(result.realtimeFactor)}"
        )
    }

    private fun showError(
        label: String,
        throwable: Throwable,
    ) {
        transcriptView.text = "ASR ERROR"
        setStatus(
            "$label\n" +
                "${throwable::class.java.simpleName}\n" +
                (throwable.message ?: "Unknown error")
        )
    }

    private fun setStatus(message: String) {
        statusView.text = message
    }

    private fun elapsedSeconds(startNanoseconds: Long): Double =
        (System.nanoTime() - startNanoseconds) / 1_000_000_000.0

    private fun formatSeconds(seconds: Double): String = "%.3f".format(seconds)

    private fun formatRtf(value: Double): String = "%.3f".format(value)

    private companion object {
        const val LOG_TAG = "WhisperTest"
        const val MODEL_RELATIVE_PATH = "models/ggml-base.en.bin"
        const val BUNDLED_WAV_ASSET = "samples/hello_survey.wav"
        const val THREAD_COUNT = 4
        const val BENCHMARK_RUN_COUNT = 5
        const val REQUIRED_SAMPLE_RATE_HZ = 16_000
        const val MAX_RECORDING_SECONDS = 30
        const val MICROPHONE_PERMISSION_REQUEST = 1001
    }
}
