package com.negi.surveycore.ai.backend.llamacpp

import com.negi.surveycore.ai.backend.GenerationRequest
import com.negi.surveycore.ai.backend.GenerationResult
import com.negi.surveycore.ai.backend.TextGenerationBackend
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Text-generation runtime backend backed by llama.cpp and a local GGUF model.
 *
 * This class owns one persistent native model/context instance.
 *
 * It intentionally contains no model-specific prompt manipulation, output
 * normalization, survey semantics, or protocol parsing.
 *
 * Model-specific behavior is applied outside this runtime adapter through
 * ProcessingTextGenerationBackend.
 */
class LlamaCppBackend(
    private val modelPath: String,
    private val contextSize: Int = DEFAULT_CONTEXT_SIZE,
) : TextGenerationBackend {

    override val backendId: String =
        "llama.cpp"

    private val lifecycleMutex =
        Mutex()

    @Volatile
    private var nativeHandle: Long =
        0L

    @Volatile
    private var closed: Boolean =
        false

    /**
     * Returns true when a persistent native model/context instance is ready
     * for generation.
     */
    override fun isReady(): Boolean =
        !closed &&
                nativeHandle != 0L

    /**
     * Loads the GGUF model and creates the persistent native inference
     * context.
     *
     * Repeated calls are safe and become no-ops after initialization.
     */
    override suspend fun initialize() {
        lifecycleMutex.withLock {
            check(!closed) {
                "LlamaCppBackend has already been closed."
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
                "GGUF model not found: ${modelFile.absolutePath}"
            }

            check(
                modelFile.length() > 0L
            ) {
                "GGUF model is empty: ${modelFile.absolutePath}"
            }

            val createdHandle =
                withContext(
                    Dispatchers.IO
                ) {
                    LlamaCppNative.create(
                        modelPath =
                            modelFile.absolutePath,
                        contextSize =
                            contextSize,
                    )
                }

            check(
                createdHandle != 0L
            ) {
                "llama.cpp returned an invalid native handle."
            }

            nativeHandle =
                createdHandle
        }
    }

    /**
     * Generates one independent response through the persistent llama.cpp
     * model/context instance.
     *
     * The native layer clears context memory before each request so separate
     * generation calls do not implicitly share conversation state.
     */
    override suspend fun generate(
        request: GenerationRequest,
    ): GenerationResult {
        initialize()

        check(!closed) {
            "LlamaCppBackend has already been closed."
        }

        val handle =
            nativeHandle

        check(
            handle != 0L
        ) {
            "LlamaCppBackend is not initialized."
        }

        return try {
            val text =
                withContext(
                    Dispatchers.IO
                ) {
                    LlamaCppNative.generate(
                        handle =
                            handle,
                        systemInstruction =
                            request.systemInstruction,
                        prompt =
                            request.prompt,
                        maxOutputTokens =
                            request.maxOutputTokens,
                        temperature =
                            request.temperature,
                    )
                }

            GenerationResult(
                text =
                    text,
            )
        } catch (
            cancellation: CancellationException
        ) {
            throw cancellation
        }
    }

    /**
     * Releases the persistent native llama.cpp model and context.
     *
     * close() is intentionally idempotent.
     */
    override fun close() {
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
            LlamaCppNative.close(
                handle
            )
        }
    }

    private companion object {

        const val DEFAULT_CONTEXT_SIZE =
            2048
    }
}