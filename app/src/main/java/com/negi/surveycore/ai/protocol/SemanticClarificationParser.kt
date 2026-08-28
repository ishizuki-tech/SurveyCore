package com.negi.surveycore.ai.protocol

import com.negi.surveycore.survey.core.ai.SemanticValidationResult

/**
 * Parses the protocol used specifically for semantic clarification
 * generation.
 *
 * Unlike the legacy SemanticValidationParser, this parser does not accept
 * VALID because overall validity has already been determined by deterministic
 * per-criterion evaluation in ModelSurveyAi.
 */
object SemanticClarificationParser {

    private const val CLARIFY_PREFIX =
        "CLARIFY:"

    fun parse(
        rawOutput: String,
    ): SemanticValidationResult {
        val output =
            rawOutput.trim()

        if (
            !output.startsWith(
                CLARIFY_PREFIX,
                ignoreCase =
                    true,
            )
        ) {
            return SemanticValidationResult.Failed(
                reason =
                    "Unexpected semantic clarification output: $output"
            )
        }

        val question =
            output
                .substring(
                    CLARIFY_PREFIX.length
                )
                .trim()

        if (
            question.isBlank()
        ) {
            return SemanticValidationResult.Failed(
                reason =
                    "Model returned CLARIFY without a question."
            )
        }

        return SemanticValidationResult.Clarify(
            question =
                question
        )
    }
}