package com.negi.surveycore

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.negi.surveycore.ai.backend.GenerationRequest
import com.negi.surveycore.ai.backend.ProcessingTextGenerationBackend
import com.negi.surveycore.ai.backend.TextGenerationBackend
import com.negi.surveycore.ai.backend.llamacpp.LlamaCppBackend
import com.negi.surveycore.ai.backend.llamacpp.QwenNoThinkPromptProcessor
import com.negi.surveycore.ai.backend.llamacpp.QwenThinkingOutputProcessor
import java.io.File
import kotlinx.coroutines.launch

/**
 * Temporary development Activity for validating the complete Qwen text
 * generation backend chain through the production TextGenerationBackend
 * abstraction.
 *
 * LlamaCppBackend owns only llama.cpp runtime responsibilities.
 * Qwen-specific prompt and output processing is applied by the common
 * ProcessingTextGenerationBackend decorator.
 */
class LlamaCppSmokeActivity : ComponentActivity() {

    private var backend: TextGenerationBackend? =
        null

    override fun onCreate(
        savedInstanceState: Bundle?,
    ) {
        super.onCreate(savedInstanceState)

        val outputView =
            TextView(this).apply {
                text =
                    "Preparing Qwen / llama.cpp backend test..."

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
                    "GGUF model not found: ${modelFile.absolutePath}"

                Log.e(
                    LOG_TAG,
                    message,
                )

                outputView.text =
                    message

                return@launch
            }

            /*
             * LlamaCppBackend owns only the native llama.cpp runtime.
             */
            val runtimeBackend =
                LlamaCppBackend(
                    modelPath =
                        modelFile.absolutePath,
                    contextSize =
                        CONTEXT_SIZE,
                )

            /*
             * Qwen-specific behavior is applied outside the runtime backend.
             *
             * The smoke-test prompts intentionally do not contain /no_think.
             * QwenNoThinkPromptProcessor must add that model-specific
             * directive automatically.
             */
            val processingBackend =
                ProcessingTextGenerationBackend(
                    delegate =
                        runtimeBackend,
                    promptProcessor =
                        QwenNoThinkPromptProcessor,
                    outputProcessor =
                        QwenThinkingOutputProcessor,
                )

            backend =
                processingBackend

            try {
                val initializeStart =
                    System.nanoTime()

                processingBackend.initialize()

                val initializeSeconds =
                    elapsedSeconds(
                        initializeStart
                    )

                Log.d(
                    LOG_TAG,
                    "Backend initialized; " +
                            "ready=${processingBackend.isReady()}; " +
                            "runtime=${processingBackend.backendId}; " +
                            "seconds=${formatSeconds(initializeSeconds)}",
                )

                val firstStart =
                    System.nanoTime()

                val firstResult =
                    processingBackend.generate(
                        GenerationRequest(
                            systemInstruction =
                                "Follow the user's instruction exactly.",
                            prompt =
                                "Return exactly the single word HELLO " +
                                        "and nothing else.",
                            maxOutputTokens =
                                MAX_OUTPUT_TOKENS,
                            temperature =
                                0f,
                        )
                    )

                val firstSeconds =
                    elapsedSeconds(
                        firstStart
                    )

                Log.d(
                    LOG_TAG,
                    "Backend generation #1; " +
                            "seconds=${formatSeconds(firstSeconds)}; " +
                            "output='${escapeForLog(firstResult.text)}'",
                )

                val secondStart =
                    System.nanoTime()

                val secondResult =
                    processingBackend.generate(
                        GenerationRequest(
                            systemInstruction =
                                "Follow the user's instruction exactly.",
                            prompt =
                                "Return exactly the single word WORLD " +
                                        "and nothing else.",
                            maxOutputTokens =
                                MAX_OUTPUT_TOKENS,
                            temperature =
                                0f,
                        )
                    )

                val secondSeconds =
                    elapsedSeconds(
                        secondStart
                    )

                Log.d(
                    LOG_TAG,
                    "Backend generation #2; " +
                            "seconds=${formatSeconds(secondSeconds)}; " +
                            "output='${escapeForLog(secondResult.text)}'",
                )

                outputView.text =
                    """
                    Qwen / llama.cpp backend READY

                    runtime:
                    ${processingBackend.backendId}

                    initialize:
                    ${formatSeconds(initializeSeconds)} sec

                    ready:
                    ${processingBackend.isReady()}

                    generation #1:
                    ${formatSeconds(firstSeconds)} sec
                    ${firstResult.text}

                    generation #2:
                    ${formatSeconds(secondSeconds)} sec
                    ${secondResult.text}
                    """.trimIndent()
            } catch (
                throwable: Throwable
            ) {
                Log.e(
                    LOG_TAG,
                    "Qwen / llama.cpp backend test failed.",
                    throwable,
                )

                outputView.text =
                    """
                    Qwen / llama.cpp backend ERROR

                    ${throwable::class.java.simpleName}

                    ${throwable.message ?: "Unknown error"}
                    """.trimIndent()
            }
        }
    }

    override fun onDestroy() {
        /*
         * Closing the decorator propagates close() to LlamaCppBackend and
         * releases the persistent native model/context.
         */
        backend?.close()

        backend =
            null

        super.onDestroy()
    }

    /**
     * Returns elapsed wall-clock time in seconds.
     */
    private fun elapsedSeconds(
        startNanoseconds: Long,
    ): Double =
        (
                System.nanoTime() -
                        startNanoseconds
                ) /
                1_000_000_000.0

    /**
     * Formats timing values consistently for logcat and the temporary UI.
     */
    private fun formatSeconds(
        seconds: Double,
    ): String =
        "%.3f".format(
            seconds
        )

    /**
     * Escapes line-control characters for one-line logcat diagnostics.
     */
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
            "LlamaCppBackend"

        const val MODEL_RELATIVE_PATH =
            "models/Qwen3-1.7B-Q4_K_M.gguf"

        const val CONTEXT_SIZE =
            2048

        const val MAX_OUTPUT_TOKENS =
            32
    }
}