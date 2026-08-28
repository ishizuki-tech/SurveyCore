package com.negi.surveycore.ai.fake

import com.negi.surveycore.survey.core.ai.FollowUpEvaluationRequest
import com.negi.surveycore.survey.core.ai.FollowUpEvaluationResult
import com.negi.surveycore.survey.core.ai.ResponseEvaluationRequest
import com.negi.surveycore.survey.core.ai.ResponseEvaluationResult
import com.negi.surveycore.survey.core.ai.SemanticValidationRequest
import com.negi.surveycore.survey.core.ai.SemanticValidationResult
import com.negi.surveycore.survey.core.ai.SurveyAi

/**
 * Scriptable SurveyAi implementation used for tests and local development.
 *
 * This class intentionally contains no survey-specific behavior.
 * Callers inject deterministic handlers for the AI operations they want
 * to exercise.
 *
 * Legacy handlers remain available while Survey Core migrates to unified
 * response evaluation.
 */
class FakeSurveyAi(
    private val validationHandler:
    suspend (SemanticValidationRequest) -> SemanticValidationResult =
        { SemanticValidationResult.Valid },

    private val followUpHandler:
    suspend (FollowUpEvaluationRequest) -> FollowUpEvaluationResult =
        { FollowUpEvaluationResult.Satisfied },

    private val responseEvaluationHandler:
    suspend (ResponseEvaluationRequest) -> ResponseEvaluationResult =
        {
            ResponseEvaluationResult.Done(
                sufficiency = 100,
            )
        },
) : SurveyAi {

    override suspend fun evaluateResponse(
        request: ResponseEvaluationRequest,
    ): ResponseEvaluationResult {
        return responseEvaluationHandler(request)
    }

    override suspend fun validateAnswer(
        request: SemanticValidationRequest,
    ): SemanticValidationResult {
        return validationHandler(request)
    }

    override suspend fun evaluateFollowUp(
        request: FollowUpEvaluationRequest,
    ): FollowUpEvaluationResult {
        return followUpHandler(request)
    }
}
