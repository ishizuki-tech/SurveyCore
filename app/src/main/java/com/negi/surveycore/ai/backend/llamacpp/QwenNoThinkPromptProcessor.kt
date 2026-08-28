package com.negi.surveycore.ai.backend.llamacpp

import com.negi.surveycore.ai.backend.GenerationPromptProcessor

/**
 * Requests Qwen3 non-thinking generation.
 *
 * Qwen3 supports the /no_think directive for requests where extended
 * reasoning output is undesirable. Survey protocol responses are expected
 * to be short and machine-parseable, so this processor appends the
 * directive to the user prompt.
 */
object QwenNoThinkPromptProcessor : GenerationPromptProcessor {

    override fun process(
        prompt: String,
    ): String {
        val trimmedPrompt =
            prompt.trimEnd()

        if (
            trimmedPrompt.endsWith(
                NO_THINK_DIRECTIVE,
                ignoreCase =
                    true,
            )
        ) {
            return trimmedPrompt
        }

        return buildString {
            append(
                trimmedPrompt
            )

            append(
                "\n\n"
            )

            append(
                NO_THINK_DIRECTIVE
            )
        }
    }

    private const val NO_THINK_DIRECTIVE =
        "/no_think"
}