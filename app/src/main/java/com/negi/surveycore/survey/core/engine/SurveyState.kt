package com.negi.surveycore.survey.core.engine

/**
 * High-level runtime phase of a survey session.
 *
 * SurveyEngine owns all state transitions.
 *
 * The response-evaluation phases are the target architecture.
 * Legacy validation phases remain temporarily available during migration.
 */
enum class SurveyPhase {
    NOT_STARTED,
    AWAITING_MAJOR_ANSWER,

    EVALUATING_RESPONSE,
    AWAITING_RESPONSE_FOLLOW_UP,

    VALIDATING_MAJOR_ANSWER,
    AWAITING_VALIDATION_CLARIFICATION,

    EVALUATING_FOLLOW_UP_TARGET,
    AWAITING_FOLLOW_UP_ANSWER,

    COMPLETE,
}

/**
 * Immutable snapshot of the current engine state.
 */
data class SurveyState(
    val phase: SurveyPhase = SurveyPhase.NOT_STARTED,
    val currentNodeId: String? = null,
    val activeLanguage: String? = null,
)
