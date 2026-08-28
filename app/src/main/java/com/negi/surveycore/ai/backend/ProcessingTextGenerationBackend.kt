package com.negi.surveycore.ai.backend

/**
 * Decorates a TextGenerationBackend with optional prompt preprocessing and
 * output postprocessing.
 *
 * Model-specific behavior belongs here rather than inside runtime adapters
 * such as LiteRtLmBackend or LlamaCppBackend.
 *
 * This allows the same runtime backend to support different model families
 * without introducing model-specific logic into the runtime implementation.
 */
class ProcessingTextGenerationBackend(
    private val delegate: TextGenerationBackend,
    private val promptProcessor: GenerationPromptProcessor =
        GenerationPromptProcessor.None,
    private val outputProcessor: GenerationOutputProcessor =
        GenerationOutputProcessor.None,
) : TextGenerationBackend {

    /**
     * Preserve the runtime backend identifier so higher layers continue to
     * identify the actual inference runtime.
     */
    override val backendId: String
        get() =
            delegate.backendId

    /**
     * Reports the readiness of the underlying runtime backend.
     */
    override fun isReady(): Boolean =
        delegate.isReady()

    /**
     * Initializes the underlying runtime backend.
     */
    override suspend fun initialize() {
        delegate.initialize()
    }

    /**
     * Applies model-specific prompt preprocessing, delegates generation to
     * the runtime backend, and then applies model-specific output processing.
     */
    override suspend fun generate(
        request: GenerationRequest,
    ): GenerationResult {
        val processedPrompt =
            promptProcessor.process(
                request.prompt
            )

        val processedRequest =
            GenerationRequest(
                systemInstruction =
                    request.systemInstruction,
                prompt =
                    processedPrompt,
                maxOutputTokens =
                    request.maxOutputTokens,
                temperature =
                    request.temperature,
            )

        val rawResult =
            delegate.generate(
                processedRequest
            )

        return GenerationResult(
            text =
                outputProcessor.process(
                    rawResult.text
                ),
        )
    }

    /**
     * Releases resources owned by the underlying runtime backend.
     */
    override fun close() {
        delegate.close()
    }
}