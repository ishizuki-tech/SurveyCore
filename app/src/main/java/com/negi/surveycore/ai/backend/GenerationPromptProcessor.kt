package com.negi.surveycore.ai.backend

/**
 * Applies optional model-specific preprocessing to a generation prompt.
 *
 * Text-generation backends remain model-agnostic by delegating optional
 * prompt transformation to an injected processor.
 */
fun interface GenerationPromptProcessor {

    /**
     * Returns the prompt that should be sent to the model runtime.
     */
    fun process(
        prompt: String,
    ): String

    companion object {

        /**
         * Default processor that leaves the prompt unchanged.
         */
        val None =
            GenerationPromptProcessor { prompt ->
                prompt
            }
    }
}