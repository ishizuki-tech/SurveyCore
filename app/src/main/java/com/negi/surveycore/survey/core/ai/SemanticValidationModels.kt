package com.negi.surveycore.survey.core.ai

/**
 * One clarification exchange collected while validating
 * a major survey answer.
 */
data class ClarificationExchange(
    val question: String,
    val answer: String,
)

/**
 * Input provided to semantic answer validation.
 *
 * The original answer and all clarification exchanges are supplied
 * explicitly so that an AI implementation does not need to maintain
 * hidden conversational state.
 */
data class SemanticValidationRequest(
    val surveyId: String,
    val language: String,
    val interviewerInstruction: String,
    val questionId: String,
    val question: String,
    val originalAnswer: String,
    val validationGoal: String,
    val criteria: List<String>,
    val previousClarifications: List<ClarificationExchange>,
)

/**
 * Result of semantic answer validation.
 */
sealed interface SemanticValidationResult {

    /**
     * The answer and accumulated clarification evidence satisfy
     * the semantic validation requirements.
     */
    data object Valid : SemanticValidationResult

    /**
     * More information is required before the answer can be accepted.
     */
    data class Clarify(
        val question: String,
    ) : SemanticValidationResult

    /**
     * AI evaluation could not be completed reliably.
     *
     * Failure is intentionally different from Valid.
     */
    data class Failed(
        val reason: String,
    ) : SemanticValidationResult
}