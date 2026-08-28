package com.negi.surveycore.ai.backend.llamacpp

import com.negi.surveycore.ai.backend.GenerationOutputProcessor

/**
 * Removes a leading Qwen thinking block from generated output.
 *
 * Qwen3 may emit <think>...</think> even when non-thinking behavior is
 * requested. SurveyCore protocol parsers must receive only the final answer.
 */
object QwenThinkingOutputProcessor : GenerationOutputProcessor {

    private val leadingThinkingBlock =
        Regex(
            pattern =
                """^\s*<think>.*?</think>\s*""",
            options =
                setOf(
                    RegexOption.DOT_MATCHES_ALL,
                ),
        )

    override fun process(
        text: String,
    ): String =
        text
            .replaceFirst(
                leadingThinkingBlock,
                "",
            )
            .trim()
}