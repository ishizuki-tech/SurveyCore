package com.negi.surveycore.survey.core.model

/**
 * Definition of one major survey question.
 */
data class QuestionDefinition(
    val prompt: LocalizedText,
    val answer: AnswerDefinition,
    val followUp: FollowUpDefinition = FollowUpDefinition.disabled(),
)