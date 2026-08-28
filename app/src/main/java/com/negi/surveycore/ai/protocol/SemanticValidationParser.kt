package com.negi.surveycore.ai.protocol

import com.negi.surveycore.survey.core.ai.SemanticValidationResult

/**
 * Parses the small semantic-validation protocol used by ModelSurveyAi.
 *
 * Supported protocol:
 *
 * VALID
 *
 * CLARIFY: <one clarification question>
 *
 * Malformed or unexpected model output is treated as failure rather
 * than silently accepting the respondent answer.
 */
object SemanticValidationParser {

    private const val VALID_TOKEN =
        "VALID"

    private const val CLARIFY_PREFIX =
        "CLARIFY:"

    fun parse(
        rawOutput: String,
    ): SemanticValidationResult {
        val output =
            rawOutput.trim()

        if (output.equals(
                VALID_TOKEN,
                ignoreCase = true,
            )
        ) {
            return SemanticValidationResult.Valid
        }

        if (
            output.startsWith(
                CLARIFY_PREFIX,
                ignoreCase = true,
            )
        ) {
            val question =
                output
                    .substring(
                        CLARIFY_PREFIX.length
                    )
                    .trim()

            if (question.isBlank()) {
                return SemanticValidationResult.Failed(
                    reason =
                        "Model returned CLARIFY without a question."
                )
            }

            return SemanticValidationResult.Clarify(
                question = question
            )
        }

        return SemanticValidationResult.Failed(
            reason =
                "Unexpected semantic validation output: $output"
        )
    }
}