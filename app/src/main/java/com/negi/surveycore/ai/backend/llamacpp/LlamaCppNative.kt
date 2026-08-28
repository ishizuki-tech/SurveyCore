package com.negi.surveycore.ai.backend.llamacpp

/**
 * Low-level JNI bridge to the embedded llama.cpp runtime.
 *
 * This object deliberately exposes native primitives only. Survey-specific
 * behavior belongs in higher layers such as ModelSurveyAi.
 */
object LlamaCppNative {

    init {
        System.loadLibrary(
            "survey_llama"
        )
    }

    /**
     * Returns diagnostic information about the linked llama.cpp runtime.
     */
    external fun nativeInfo(): String

    /**
     * Loads a GGUF model, creates a temporary context, returns model
     * diagnostics, and releases all resources.
     *
     * This method is retained temporarily for development smoke testing.
     */
    external fun smokeTestModel(
        modelPath: String,
        contextSize: Int,
    ): String

    /**
     * Performs a complete temporary model-load and text-generation cycle.
     *
     * This method is retained temporarily so the persistent implementation
     * can be compared with the previously validated smoke-test path.
     */
    external fun smokeTestGenerate(
        modelPath: String,
        prompt: String,
        contextSize: Int,
        maxOutputTokens: Int,
    ): String

    /**
     * Creates a persistent native llama.cpp instance.
     *
     * The returned handle owns both the llama_model and llama_context and
     * remains valid until close() is called.
     *
     * @return Non-zero opaque native handle.
     */
    external fun create(
        modelPath: String,
        contextSize: Int,
    ): Long

    /**
     * Generates text using an existing persistent native instance.
     *
     * Each call represents an independent generation request. The native
     * context memory is reset before the request so previous prompts do not
     * implicitly become conversation history.
     */
    external fun generate(
        handle: Long,
        systemInstruction: String?,
        prompt: String,
        maxOutputTokens: Int,
        temperature: Float,
    ): String

    /**
     * Releases the persistent model, context, and associated native state.
     *
     * The handle must not be used again after this call.
     */
    external fun close(
        handle: Long,
    )
}