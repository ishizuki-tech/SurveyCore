package com.negi.surveycore.ai.backend

/**
 * Runtime-neutral text-generation backend.
 *
 * Implementations may use:
 *
 * - Fake deterministic responses
 * - Google AI Edge LiteRT-LM
 * - llama.cpp
 * - Another local or remote inference runtime
 *
 * Survey-specific logic must not be implemented in this interface.
 */
interface TextGenerationBackend {

    /**
     * Stable identifier for diagnostics and configuration.
     */
    val backendId: String

    /**
     * Initializes the underlying inference runtime.
     *
     * Implementations should reject generation requests until
     * initialization has completed successfully.
     */
    suspend fun initialize()

    /**
     * Generates text from a system instruction and user prompt.
     */
    suspend fun generate(
        request: GenerationRequest,
    ): GenerationResult

    /**
     * Returns true when the backend can accept generation requests.
     */
    fun isReady(): Boolean

    /**
     * Releases runtime resources.
     *
     * Calling close more than once should be safe.
     */
    fun close()
}