package com.negi.surveycore.survey.core.engine

import com.negi.surveycore.survey.core.ai.ClarificationExchange
import com.negi.surveycore.survey.core.ai.ResponseFollowUpExchange

/**
 * Accepted answer for one major survey question.
 *
 * The respondent's original answer is preserved exactly.
 * Follow-up exchanges are stored separately rather than being silently merged
 * or rewritten by AI.
 *
 * [clarifications] is retained temporarily for legacy semantic-validation
 * sessions during migration.
 */
data class MajorAnswerRecord(
    val questionId: String,
    val originalAnswer: String,
    val clarifications: List<ClarificationExchange> = emptyList(),
    val responseFollowUps: List<ResponseFollowUpExchange> = emptyList(),
    val finalSufficiency: Int? = null,
)

/**
 * Runtime data collected during one survey execution.
 */
data class SurveySession(
    val answers: Map<String, MajorAnswerRecord> = emptyMap(),
)
