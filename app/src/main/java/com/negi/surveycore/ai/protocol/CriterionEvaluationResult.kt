package com.negi.surveycore.ai.protocol

/**
 * Internal result produced while evaluating one semantic validation
 * criterion.
 *
 * Criterion-level evaluation is an implementation detail of ModelSurveyAi
 * and is intentionally kept outside the Survey Core contract.
 */
sealed interface CriterionEvaluationResult {

    /**
     * The model claims that the criterion is supported and provides a quote
     * copied from respondent evidence.
     *
     * ModelSurveyAi must independently verify that this quote actually exists
     * in respondent-provided evidence before accepting the criterion.
     */
    data class Supported(
        val evidenceQuote: String,
    ) : CriterionEvaluationResult

    /**
     * The model could not find sufficient respondent evidence for the
     * criterion.
     */
    data object Missing : CriterionEvaluationResult

    /**
     * The model response did not conform to the criterion-evaluation
     * protocol.
     */
    data class Failed(
        val reason: String,
    ) : CriterionEvaluationResult
}