package com.negi.surveycore.ai.backend

/**
 * Applies model-specific normalization to raw generated text.
 *
 * Text-generation backends remain model-agnostic by delegating optional
 * output cleanup to an injected processor.
 */
fun interface GenerationOutputProcessor {

    fun process(
        text: String,
    ): String

    companion object {

        val None =
            GenerationOutputProcessor { text ->
                text
            }
    }
}