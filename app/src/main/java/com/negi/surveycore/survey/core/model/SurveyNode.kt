package com.negi.surveycore.survey.core.model

/**
 * A node in the deterministic survey flow.
 */
sealed interface SurveyNode {

    val id: String
    val title: LocalizedText?
}

/**
 * Entry point of a survey.
 */
data class StartNode(
    override val id: String,
    override val title: LocalizedText? = null,
    val nextNodeId: String,
) : SurveyNode

/**
 * A node that asks a respondent for an answer.
 */
data class QuestionNode(
    override val id: String,
    override val title: LocalizedText? = null,
    val question: QuestionDefinition,
    val navigation: NavigationDefinition,
) : SurveyNode

/**
 * Optional review step before survey completion.
 */
data class ReviewNode(
    override val id: String,
    override val title: LocalizedText? = null,
    val navigation: NavigationDefinition,
) : SurveyNode

/**
 * Terminal survey node.
 */
data class EndNode(
    override val id: String,
    override val title: LocalizedText? = null,
    val completionMessage: LocalizedText? = null,
) : SurveyNode

data class SurveyFlowDefinition(
    val startNodeId: String,
    val nodes: List<SurveyNode>,
)