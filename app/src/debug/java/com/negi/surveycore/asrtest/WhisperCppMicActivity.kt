package com.negi.surveycore.asrtest

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.negi.surveycore.asr.SpeechRecognitionBackend
import com.negi.surveycore.asr.whispercpp.WhisperCppBackend
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-only microphone test for the English whisper.cpp backend.
 *
 * This activity intentionally stops at microphone -> PCM -> whisper.cpp -> text.
 * It does not call SurveyController or any SLM backend.
 */
class WhisperCppMicActivity : ComponentActivity() {

    private lateinit var statusView: TextView
    private lateinit var transcriptView: TextView
    private lateinit var recordButton: Button

    private var backend: SpeechRecognitionBackend? =
        null

    private var recorder: DebugMicrophoneRecorder? =
        null

    private var recordingJob: Job? =
        null

    private var backendReady: Boolean =
        false

    private val microphonePermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                startRecording()
            } else {
                setStatus(
                    "Microphone permission denied."
                )
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?,
    ) {
        super.onCreate(savedInstanceState)

        setContentView(
            buildContentView()
        )

        initializeBackend()
    }

    override fun onDestroy() {
        recorder?.requestStop()
        recorder = null

        backend?.close()
        backend = null

        super.onDestroy()
    }

    private fun buildContentView(): ScrollView {
        val padding =
            (24 * resources.displayMetrics.density)
                .toInt()

        val container =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL

                gravity =
                    Gravity.CENTER_HORIZONTAL

                setPadding(
                    padding,
                    padding,
                    padding,
                    padding,
                )
            }

        val titleView =
            TextView(this).apply {
                text =
                    "English whisper.cpp Microphone ASR"

                textSize =
                    24f
            }

        statusView =
            TextView(this).apply {
                text =
                    "Initializing ggml-base.en.bin..."

                textSize =
                    18f

                setPadding(
                    0,
                    padding,
                    0,
                    padding,
                )
            }

        recordButton =
            Button(this).apply {
                text =
                    "Start recording"

                isEnabled =
                    false

                setOnClickListener {
                    if (
                        recordingJob == null
                    ) {
                        ensurePermissionAndStart()
                    } else {
                        stopRecording()
                    }
                }
            }

        transcriptView =
            TextView(this).apply {
                text =
                    "Transcript will appear here."

                textSize =
                    20f

                setPadding(
                    0,
                    padding,
                    0,
                    padding,
                )
            }

        container.addView(titleView)
        container.addView(statusView)
        container.addView(recordButton)
        container.addView(transcriptView)

        return ScrollView(this).apply {
            addView(container)
        }
    }

    private fun initializeBackend() {
        lifecycleScope.launch {
            val modelFile =
                File(
                    filesDir,
                    MODEL_RELATIVE_PATH,
                )

            if (
                !modelFile.isFile
            ) {
                setStatus(
                    "Whisper model not found:\n${modelFile.absolutePath}"
                )

                return@launch
            }

            try {
                val runtimeBackend =
                    WhisperCppBackend(
                        modelPath =
                            modelFile.absolutePath,
                        threadCount =
                            THREAD_COUNT,
                    )

                backend =
                    runtimeBackend

                val initializeStart =
                    System.nanoTime()

                runtimeBackend.initialize()

                backendReady =
                    true

                recordButton.isEnabled =
                    true

                setStatus(
                    "READY\n" +
                        "model: ${modelFile.name}\n" +
                        "initialize: ${formatSeconds(elapsedSeconds(initializeStart))} sec\n\n" +
                        "Tap Start recording, speak English, then tap Stop."
                )
            } catch (
                throwable: Throwable
            ) {
                Log.e(
                    LOG_TAG,
                    "Failed to initialize microphone ASR test.",
                    throwable,
                )

                setStatus(
                    "Initialization error:\n" +
                        "${throwable::class.java.simpleName}\n" +
                        "${throwable.message ?: "Unknown error"}"
                )
            }
        }
    }

    private fun ensurePermissionAndStart() {
        if (
            !backendReady
        ) {
            return
        }

        when (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            )
        ) {
            PackageManager.PERMISSION_GRANTED ->
                startRecording()

            else ->
                microphonePermissionLauncher.launch(
                    Manifest.permission.RECORD_AUDIO
                )
        }
    }

    private fun startRecording() {
        if (
            recordingJob != null ||
            !backendReady
        ) {
            return
        }

        val microphoneRecorder =
            DebugMicrophoneRecorder(
                sampleRateHz =
                    REQUIRED_SAMPLE_RATE_HZ,
            )

        recorder =
            microphoneRecorder

        recordButton.text =
            "Stop and transcribe"

        transcriptView.text =
            "Listening..."

        setStatus(
            "RECORDING\n16 kHz mono PCM16\nMaximum ${MAX_RECORDING_SECONDS} sec"
        )

        val job =
            lifecycleScope.launch {
                try {
                    val recording =
                        withContext(
                            Dispatchers.IO
                        ) {
                            microphoneRecorder.recordBlocking(
                                maxDurationSeconds =
                                    MAX_RECORDING_SECONDS,
                            )
                        }

                    recorder =
                        null

                    recordButton.text =
                        "Start recording"

                    recordButton.isEnabled =
                        false

                    val audioSeconds =
                        recording.samples.size.toDouble() /
                            recording.sampleRateHz.toDouble()

                    setStatus(
                        "TRANSCRIBING\n" +
                            "captured: ${formatSeconds(audioSeconds)} sec"
                    )

                    val runtimeBackend =
                        checkNotNull(backend) {
                            "Whisper backend is unavailable."
                        }

                    val result =
                        runtimeBackend.transcribe(
                            samples =
                                recording.samples,
                            sampleRateHz =
                                recording.sampleRateHz,
                        )

                    transcriptView.text =
                        result.text.ifBlank {
                            "(No speech recognized)"
                        }

                    setStatus(
                        "READY\n" +
                            "audio: ${formatSeconds(result.audioDurationMs / 1000.0)} sec\n" +
                            "inference: ${formatSeconds(result.inferenceDurationMs / 1000.0)} sec\n" +
                            "RTF: ${formatRtf(result.realtimeFactor)}"
                    )

                    Log.d(
                        LOG_TAG,
                        "audioMs=${result.audioDurationMs}; " +
                            "inferenceMs=${result.inferenceDurationMs}; " +
                            "rtf=${formatRtf(result.realtimeFactor)}; " +
                            "text='${escapeForLog(result.text)}'",
                    )
                } catch (
                    throwable: Throwable
                ) {
                    Log.e(
                        LOG_TAG,
                        "Microphone ASR test failed.",
                        throwable,
                    )

                    transcriptView.text =
                        "ASR ERROR"

                    setStatus(
                        "${throwable::class.java.simpleName}\n" +
                            "${throwable.message ?: "Unknown error"}"
                    )
                } finally {
                    recorder =
                        null

                    recordingJob =
                        null

                    recordButton.text =
                        "Start recording"

                    recordButton.isEnabled =
                        backendReady
                }
            }

        recordingJob =
            job
    }

    private fun stopRecording() {
        recordButton.isEnabled =
            false

        setStatus(
            "Stopping microphone..."
        )

        recorder?.requestStop()
    }

    private fun setStatus(
        message: String,
    ) {
        statusView.text =
            message
    }

    private fun elapsedSeconds(
        startNanoseconds: Long,
    ): Double =
        (
            System.nanoTime() -
                startNanoseconds
        ) /
            1_000_000_000.0

    private fun formatSeconds(
        seconds: Double,
    ): String =
        "%.3f".format(seconds)

    private fun formatRtf(
        value: Double,
    ): String =
        "%.3f".format(value)

    private fun escapeForLog(
        text: String,
    ): String =
        text
            .replace(
                "\r",
                "\\r",
            )
            .replace(
                "\n",
                "\\n",
            )
            .replace(
                "\t",
                "\\t",
            )

    private companion object {

        const val LOG_TAG =
            "WhisperCppMic"

        const val MODEL_RELATIVE_PATH =
            "models/ggml-base.en.bin"

        const val THREAD_COUNT =
            4

        const val REQUIRED_SAMPLE_RATE_HZ =
            16_000

        const val MAX_RECORDING_SECONDS =
            30
    }
}
