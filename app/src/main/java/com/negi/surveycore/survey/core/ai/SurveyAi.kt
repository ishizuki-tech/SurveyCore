package com.negi.surveycore.survey.core.ai

/**
 * Survey-level AI contract.
 *
 * Survey Core depends only on this interface.
 * Implementations may use a fake, LiteRT-LM, llama.cpp,
 * or another inference backend.
 *
 * Unified response evaluation is the target interview architecture.
 * The legacy semantic-validation and target-follow-up methods remain
 * temporarily available while existing survey definitions are migrated.
 */
interface SurveyAi {

    /**
     * Evaluates one respondent turn using the complete accumulated evidence
     * for the current major question.
     *
     * One invocation represents one respondent turn and should correspond to
     * one model generation in model-backed implementations.
     */
    suspend fun evaluateResponse(
        request: ResponseEvaluationRequest,
    ): ResponseEvaluationResult {
        return ResponseEvaluationResult.Failed(
            reason =
                "Unified response evaluation is not supported by this SurveyAi implementation."
        )
    }

    /**
     * Legacy semantic-validation contract retained during migration.
     */
    suspend fun validateAnswer(
        request: SemanticValidationRequest,
    ): SemanticValidationResult

    /**
     * Legacy research follow-up contract retained during migration.
     */
    suspend fun evaluateFollowUp(
        request: FollowUpEvaluationRequest,
    ): FollowUpEvaluationResult
}
