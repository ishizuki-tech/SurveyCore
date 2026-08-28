package com.negi.surveycore.survey.core.ai

/**
 * One follow-up exchange collected while evaluating a respondent's answer
 * to the current major survey question.
 *
 * The question provides conversational context for interpreting the paired
 * respondent answer.
 *
 * The respondent answer is part of the accumulated evidence supplied to the
 * SLM on the next response-evaluation turn.
 */
data class ResponseFollowUpExchange(
    val question: String,
    val answer: String,
)

/**
 * Input for one integrated SLM evaluation of the respondent's accumulated
 * answer to a major survey question.
 *
 * One call represents one respondent turn.
 *
 * The request contains the original major answer plus all previous follow-up
 * exchanges so the SLM does not need to maintain hidden conversational state.
 *
 * Survey flow, branching, follow-up limits, storage, and completion remain
 * deterministic responsibilities of Survey Core.
 */
data class ResponseEvaluationRequest(
    val surveyId: String,
    val language: String,
    val interviewerInstruction: String,
    val questionId: String,
    val question: String,
    val interviewGoal: String,
    val originalAnswer: String,
    val previousFollowUps: List<ResponseFollowUpExchange>,
)

/**
 * Result of one integrated SLM response evaluation.
 *
 * The SLM evaluates how sufficiently the accumulated respondent evidence
 * addresses the interview goal and either indicates that the interview can
 * continue or proposes exactly one useful follow-up question.
 */
sealed interface ResponseEvaluationResult {

    /**
     * The accumulated respondent evidence is sufficiently complete for the
     * current interview goal.
     *
     * Sufficiency is a semantic completeness score from 0 through 100.
     * It is not a probability or confidence estimate.
     */
    data class Done(
        val sufficiency: Int,
    ) : ResponseEvaluationResult

    /**
     * An important information gap remains and one additional follow-up
     * question is recommended.
     *
     * The gap is a short semantic description intended for diagnostics and
     * evaluation. Survey Core does not interpret the gap as business logic.
     */
    data class FollowUp(
        val sufficiency: Int,
        val gap: String,
        val question: String,
    ) : ResponseEvaluationResult

    /**
     * The response evaluation could not be completed reliably.
     *
     * Failure must never be interpreted as DONE.
     */
    data class Failed(
        val reason: String,
    ) : ResponseEvaluationResult
}