package com.negi.surveycore.survey.core.ai

/**
 * One completed research follow-up exchange.
 */
data class FollowUpExchange(
    val targetId: String,
    val question: String,
    val answer: String,
)

/**
 * Input for evaluating one specific research target.
 *
 * SurveyEngine selects the target. AI does not decide which target
 * should be evaluated next.
 */
data class FollowUpEvaluationRequest(
    val surveyId: String,
    val language: String,
    val interviewerInstruction: String,
    val questionId: String,
    val majorQuestion: String,
    val majorAnswer: String,
    val targetId: String,
    val targetDescription: String,
    val previousFollowUps: List<FollowUpExchange>,
)

/**
 * Result of evaluating one research follow-up target.
 */
sealed interface FollowUpEvaluationResult {

    /**
     * The currently selected target has enough information.
     */
    data object Satisfied : FollowUpEvaluationResult

    /**
     * One additional question should be asked for the current target.
     */
    data class Ask(
        val question: String,
    ) : FollowUpEvaluationResult

    /**
     * AI evaluation failed.
     *
     * Failure must not be interpreted as target satisfaction.
     */
    data class Failed(
        val reason: String,
    ) : FollowUpEvaluationResult
}