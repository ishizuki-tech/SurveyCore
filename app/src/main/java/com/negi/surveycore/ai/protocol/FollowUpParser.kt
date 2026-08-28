package com.negi.surveycore.ai.protocol

import com.negi.surveycore.survey.core.ai.FollowUpEvaluationResult

/**
 * Parses the research follow-up protocol returned by ModelSurveyAi.
 *
 * Supported protocol:
 *
 * SATISFIED
 *
 * FOLLOW_UP: <one follow-up question>
 *
 * Unexpected model output is treated as failure.
 */
object FollowUpParser {

    private const val SATISFIED_TOKEN =
        "SATISFIED"

    private const val FOLLOW_UP_PREFIX =
        "FOLLOW_UP:"

    fun parse(
        rawOutput: String,
    ): FollowUpEvaluationResult {
        val output =
            rawOutput.trim()

        if (
            output.equals(
                SATISFIED_TOKEN,
                ignoreCase = true,
            )
        ) {
            return FollowUpEvaluationResult.Satisfied
        }

        if (
            output.startsWith(
                FOLLOW_UP_PREFIX,
                ignoreCase = true,
            )
        ) {
            val question =
                output
                    .substring(
                        FOLLOW_UP_PREFIX.length
                    )
                    .trim()

            if (question.isBlank()) {
                return FollowUpEvaluationResult.Failed(
                    reason =
                        "Model returned FOLLOW_UP without a question."
                )
            }

            return FollowUpEvaluationResult.Ask(
                question = question
            )
        }

        return FollowUpEvaluationResult.Failed(
            reason =
                "Unexpected follow-up evaluation output: $output"
        )
    }
}