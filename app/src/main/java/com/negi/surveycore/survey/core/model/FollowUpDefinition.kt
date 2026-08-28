package com.negi.surveycore.survey.core.model

/**
 * Optional interviewer follow-up configuration.
 *
 * The target architecture uses [goal] as the semantic interview objective.
 * SurveyAi evaluates the respondent's accumulated answer against that goal
 * and may propose one useful follow-up question per respondent turn.
 *
 * [targets] is retained temporarily for compatibility with the earlier
 * research-target follow-up design and will be removed after migration.
 */
data class FollowUpDefinition(
    val enabled: Boolean,
    val maxQuestions: Int,
    val targets: List<FollowUpTargetDefinition>,
    val goal: String = "",
) {

    companion object {

        fun disabled(): FollowUpDefinition {
            return FollowUpDefinition(
                enabled = false,
                maxQuestions = 0,
                targets = emptyList(),
                goal = "",
            )
        }
    }
}

/**
 * Legacy research follow-up target.
 *
 * Retained temporarily so existing survey definitions and loaders continue
 * to compile during the response-evaluation migration.
 */
data class FollowUpTargetDefinition(
    val id: String,
    val description: String,
    val maxAttempts: Int = 1,
)
