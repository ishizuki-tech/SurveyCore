package com.negi.surveycore.ai.protocol

import com.negi.surveycore.survey.core.ai.ResponseEvaluationResult

/**
 * Parses the line-oriented protocol returned by the response-evaluation SLM.
 *
 * Expected protocol:
 *
 * REMAINING_GAP: <short description or NONE>
 * STATUS: DONE|FOLLOW_UP
 * QUESTION: <one follow-up question or NONE>
 * SUFFICIENCY: <0-100>
 *
 * REMAINING_GAP is intentionally the first generated field so the model
 * evaluates only information that still remains missing or ambiguous before
 * committing to DONE or FOLLOW_UP.
 *
 * This parser performs only mechanical protocol validation.
 * It does not evaluate whether the model's semantic judgment is correct.
 */
object ResponseEvaluationParser {

    private const val STATUS_PREFIX = "STATUS:"
    private const val REMAINING_GAP_PREFIX = "REMAINING_GAP:"
    private const val QUESTION_PREFIX = "QUESTION:"
    private const val SUFFICIENCY_PREFIX = "SUFFICIENCY:"

    private const val STATUS_DONE = "DONE"
    private const val STATUS_FOLLOW_UP = "FOLLOW_UP"
    private const val NONE = "NONE"

    fun parse(text: String): ResponseEvaluationResult {
        val lines =
            text
                .lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toList()

        if (lines.size != EXPECTED_LINE_COUNT) {
            return failed(
                "Expected exactly $EXPECTED_LINE_COUNT non-blank protocol lines, " +
                        "but received ${lines.size}.",
            )
        }

        val remainingGap =
            extractValue(
                line = lines[0],
                expectedPrefix = REMAINING_GAP_PREFIX,
            )
                ?: return failed(
                    "Expected first line to start with '$REMAINING_GAP_PREFIX'.",
                )

        val status =
            extractValue(
                line = lines[1],
                expectedPrefix = STATUS_PREFIX,
            )
                ?: return failed(
                    "Expected second line to start with '$STATUS_PREFIX'.",
                )

        val question =
            extractValue(
                line = lines[2],
                expectedPrefix = QUESTION_PREFIX,
            )
                ?: return failed(
                    "Expected third line to start with '$QUESTION_PREFIX'.",
                )

        val sufficiencyText =
            extractValue(
                line = lines[3],
                expectedPrefix = SUFFICIENCY_PREFIX,
            )
                ?: return failed(
                    "Expected fourth line to start with '$SUFFICIENCY_PREFIX'.",
                )

        val sufficiency =
            sufficiencyText.toIntOrNull()
                ?: return failed(
                    "SUFFICIENCY must be an integer from 0 through 100.",
                )

        if (sufficiency !in MIN_SUFFICIENCY..MAX_SUFFICIENCY) {
            return failed(
                "SUFFICIENCY must be between $MIN_SUFFICIENCY and $MAX_SUFFICIENCY.",
            )
        }

        return when (status) {
            STATUS_DONE ->
                parseDone(
                    sufficiency = sufficiency,
                    remainingGap = remainingGap,
                    question = question,
                )

            STATUS_FOLLOW_UP ->
                parseFollowUp(
                    sufficiency = sufficiency,
                    remainingGap = remainingGap,
                    question = question,
                )

            else ->
                failed(
                    "STATUS must be '$STATUS_DONE' or '$STATUS_FOLLOW_UP'.",
                )
        }
    }

    private fun parseDone(
        sufficiency: Int,
        remainingGap: String,
        question: String,
    ): ResponseEvaluationResult {
        /*
         * Score/status consistency is part of the wire protocol rather than
         * a survey-specific semantic judgment. Rejecting contradictory fields
         * prevents malformed model output from silently advancing the survey.
         */
        if (sufficiency < MIN_DONE_SUFFICIENCY) {
            return failed(
                "STATUS: DONE requires SUFFICIENCY from " +
                        "$MIN_DONE_SUFFICIENCY through $MAX_SUFFICIENCY.",
            )
        }

        if (remainingGap != NONE) {
            return failed(
                "STATUS: DONE requires REMAINING_GAP: NONE.",
            )
        }

        if (question != NONE) {
            return failed(
                "STATUS: DONE requires QUESTION: NONE.",
            )
        }

        return ResponseEvaluationResult.Done(
            sufficiency = sufficiency,
        )
    }

    private fun parseFollowUp(
        sufficiency: Int,
        remainingGap: String,
        question: String,
    ): ResponseEvaluationResult {
        /*
         * FOLLOW_UP and DONE use disjoint score ranges so all protocol fields
         * communicate the same control decision.
         */
        if (sufficiency > MAX_FOLLOW_UP_SUFFICIENCY) {
            return failed(
                "STATUS: FOLLOW_UP requires SUFFICIENCY from " +
                        "$MIN_SUFFICIENCY through $MAX_FOLLOW_UP_SUFFICIENCY.",
            )
        }

        if (remainingGap.isBlank() || remainingGap == NONE) {
            return failed(
                "STATUS: FOLLOW_UP requires a non-empty REMAINING_GAP.",
            )
        }

        if (question.isBlank() || question == NONE) {
            return failed(
                "STATUS: FOLLOW_UP requires a non-empty QUESTION.",
            )
        }

        return ResponseEvaluationResult.FollowUp(
            sufficiency = sufficiency,
            gap = remainingGap,
            question = question,
        )
    }

    private fun extractValue(
        line: String,
        expectedPrefix: String,
    ): String? {
        if (!line.startsWith(expectedPrefix)) {
            return null
        }

        return line
            .removePrefix(expectedPrefix)
            .trim()
    }

    private fun failed(
        reason: String,
    ): ResponseEvaluationResult {
        return ResponseEvaluationResult.Failed(
            reason = reason,
        )
    }

    private const val EXPECTED_LINE_COUNT = 4

    private const val MIN_SUFFICIENCY = 0
    private const val MAX_SUFFICIENCY = 100

    private const val MAX_FOLLOW_UP_SUFFICIENCY = 80
    private const val MIN_DONE_SUFFICIENCY = 81
}
