package com.negi.surveycore.ai.protocol

/**
 * Parses the grounded criterion-evaluation protocol.
 *
 * Supported outputs:
 *
 * SUPPORTED: <exact quote from respondent evidence>
 *
 * MISSING
 *
 * The parser validates only protocol structure. ModelSurveyAi performs a
 * separate deterministic grounding check to verify that a SUPPORTED quote
 * actually exists in respondent-provided evidence.
 */
object CriterionEvaluationParser {

    private const val SUPPORTED_PREFIX =
        "SUPPORTED:"

    private const val MISSING_TOKEN =
        "MISSING"

    fun parse(
        rawOutput: String,
    ): CriterionEvaluationResult {
        val output =
            rawOutput.trim()

        if (
            output.isEmpty()
        ) {
            return CriterionEvaluationResult.Failed(
                reason =
                    "Criterion evaluation output is empty."
            )
        }

        /*
         * The protocol intentionally allows exactly one nonblank line.
         *
         * Rejecting additional lines prevents reasoning, explanations, or
         * unrelated text from being silently accepted.
         */
        val nonBlankLines =
            output
                .lineSequence()
                .map {
                    it.trim()
                }
                .filter {
                    it.isNotEmpty()
                }
                .toList()

        if (
            nonBlankLines.size != 1
        ) {
            return CriterionEvaluationResult.Failed(
                reason =
                    "Criterion evaluation must contain exactly one " +
                            "nonblank protocol line."
            )
        }

        val line =
            nonBlankLines.single()

        if (
            line.equals(
                MISSING_TOKEN,
                ignoreCase =
                    true,
            )
        ) {
            return CriterionEvaluationResult.Missing
        }

        if (
            line.startsWith(
                SUPPORTED_PREFIX,
                ignoreCase =
                    true,
            )
        ) {
            val evidenceQuote =
                line
                    .substring(
                        SUPPORTED_PREFIX.length
                    )
                    .trim()

            if (
                evidenceQuote.isBlank()
            ) {
                return CriterionEvaluationResult.Failed(
                    reason =
                        "Model returned SUPPORTED without an evidence quote."
                )
            }

            return CriterionEvaluationResult.Supported(
                evidenceQuote =
                    evidenceQuote
            )
        }

        return CriterionEvaluationResult.Failed(
            reason =
                "Unexpected criterion evaluation output: $line"
        )
    }
}