package com.negi.surveycore.asrtest

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.negi.surveycore.asr.Pcm16WavDecoder
import com.negi.surveycore.asr.SpeechRecognitionBackend
import com.negi.surveycore.asr.whispercpp.WhisperCppBackend
import java.io.File
import kotlinx.coroutines.launch

/**
 * Debug-only activity for validating the English-only whisper.cpp runtime in
 * isolation from SurveyController and all SLM backends.
 */
class WhisperCppSmokeActivity : ComponentActivity() {

    private var backend: SpeechRecognitionBackend? =
        null

    override fun onCreate(
        savedInstanceState: Bundle?,
    ) {
        super.onCreate(savedInstanceState)

        val outputView =
            TextView(this).apply {
                text =
                    "Preparing English whisper.cpp ASR smoke test..."

                textSize =
                    18f

                setPadding(
                    32,
                    64,
                    32,
                    32,
                )
            }

        setContentView(
            outputView
        )

        lifecycleScope.launch {
            val modelFile =
                File(
                    filesDir,
                    MODEL_RELATIVE_PATH,
                )

            if (
                !modelFile.isFile
            ) {
                val message =
                    buildString {
                        appendLine(
                            "Whisper model not found:"
                        )

                        appendLine(
                            modelFile.absolutePath
                        )

                        appendLine()

                        appendLine(
                            "Run:"
                        )

                        appendLine(
                            "scripts/download_whisper_base_en.sh"
                        )

                        appendLine(
                            "scripts/install_whisper_base_en.sh"
                        )
                    }

                Log.e(
                    LOG_TAG,
                    message,
                )

                outputView.text =
                    message

                return@launch
            }

            try {
                val decodedAudio =
                    assets.open(
                        SAMPLE_ASSET_PATH
                    ).use { input ->
                        Pcm16WavDecoder.decode(
                            input
                        )
                    }

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

                val initializeSeconds =
                    elapsedSeconds(
                        initializeStart
                    )

                val result =
                    runtimeBackend.transcribe(
                        samples =
                            decodedAudio.samples,
                        sampleRateHz =
                            decodedAudio.sampleRateHz,
                    )

                val nativeInfo =
                    WhisperCppBackend.nativeInfo()

                Log.d(
                    LOG_TAG,
                    "ASR ready=${runtimeBackend.isReady()}; " +
                            "initializeSeconds=${formatSeconds(initializeSeconds)}; " +
                            "audioMs=${result.audioDurationMs}; " +
                            "inferenceMs=${result.inferenceDurationMs}; " +
                            "rtf=${formatRtf(result.realtimeFactor)}; " +
                            "text='${escapeForLog(result.text)}'",
                )

                outputView.text =
                    """
                    English whisper.cpp ASR READY

                    backend:
                    ${runtimeBackend.backendId}

                    model:
                    ${modelFile.name}

                    initialize:
                    ${formatSeconds(initializeSeconds)} sec

                    audio:
                    ${result.audioDurationMs / 1000.0} sec

                    inference:
                    ${result.inferenceDurationMs / 1000.0} sec

                    RTF:
                    ${formatRtf(result.realtimeFactor)}

                    transcript:
                    ${result.text}

                    native:
                    $nativeInfo
                    """.trimIndent()
            } catch (
                throwable: Throwable
            ) {
                Log.e(
                    LOG_TAG,
                    "English whisper.cpp ASR smoke test failed.",
                    throwable,
                )

                outputView.text =
                    """
                    English whisper.cpp ASR ERROR

                    ${throwable::class.java.simpleName}

                    ${throwable.message ?: "Unknown error"}
                    """.trimIndent()
            }
        }
    }

    override fun onDestroy() {
        backend?.close()

        backend =
            null

        super.onDestroy()
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
        "%.3f".format(
            seconds
        )

    private fun formatRtf(
        value: Double,
    ): String =
        "%.3f".format(
            value
        )

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
            "WhisperCppSmoke"

        const val MODEL_RELATIVE_PATH =
            "models/ggml-base.en.bin"

        const val SAMPLE_ASSET_PATH =
            "samples/hello_survey.wav"

        const val THREAD_COUNT =
            4
    }
}
