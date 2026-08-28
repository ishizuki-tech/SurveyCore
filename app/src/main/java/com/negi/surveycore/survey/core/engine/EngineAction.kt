package com.negi.surveycore.survey.core.engine

import com.negi.surveycore.survey.core.ai.ResponseEvaluationRequest
import com.negi.surveycore.survey.core.ai.SemanticValidationRequest

/**
 * Describes the next action required by SurveyEngine.
 *
 * SurveyEngine never directly invokes AI or UI code.
 */
sealed interface EngineAction {

    /**
     * Ask the respondent a major survey question.
     */
    data class AskMajorQuestion(
        val questionId: String,
        val prompt: String,
    ) : EngineAction

    /**
     * Ask SurveyAi to evaluate the complete accumulated respondent evidence
     * for the current major question.
     */
    data class RequestResponseEvaluation(
        val request: ResponseEvaluationRequest,
    ) : EngineAction

    /**
     * Ask one model-generated interviewer follow-up question.
     */
    data class AskResponseFollowUp(
        val questionId: String,
        val question: String,
    ) : EngineAction

    /**
     * Legacy semantic-validation request retained during migration.
     */
    data class RequestSemanticValidation(
        val request: SemanticValidationRequest,
    ) : EngineAction

    /**
     * Legacy semantic clarification retained during migration.
     */
    data class AskClarification(
        val questionId: String,
        val question: String,
    ) : EngineAction

    /**
     * Deterministic validation rejected the submitted answer.
     *
     * The respondent may submit another answer.
     */
    data class AnswerRejected(
        val questionId: String,
        val message: String,
    ) : EngineAction

    /**
     * The response-evaluation follow-up budget has been exhausted.
     *
     * The answer is not silently accepted.
     */
    data class ResponseEvaluationExhausted(
        val questionId: String,
        val message: String,
    ) : EngineAction

    /**
     * The legacy semantic clarification budget has been exhausted.
     *
     * The answer is not silently accepted.
     */
    data class SemanticValidationExhausted(
        val questionId: String,
        val message: String,
    ) : EngineAction

    /**
     * The external AI evaluator failed.
     *
     * SurveyEngine does not interpret failure as successful evaluation.
     */
    data class AiEvaluationFailed(
        val questionId: String,
        val reason: String,
    ) : EngineAction

    /**
     * A review node has been reached.
     */
    data class ShowReview(
        val nodeId: String,
    ) : EngineAction

    /**
     * The survey has completed.
     */
    data class Complete(
        val completionMessage: String?,
    ) : EngineAction
}
