package com.negi.surveycore.survey.core.model

/**
 * Defines deterministic navigation after a survey node.
 *
 * Branches are evaluated in order. If none match, defaultNextNodeId
 * is used.
 */
data class NavigationDefinition(
    val defaultNextNodeId: String?,
    val branches: List<BranchDefinition> = emptyList(),
)

data class BranchDefinition(
    val condition: ConditionDefinition,
    val nextNodeId: String,
)

data class ConditionDefinition(
    val questionId: String,
    val operator: ConditionOperator,
    val expectedValue: String,
)

enum class ConditionOperator {
    EQUALS,
    NOT_EQUALS,
    CONTAINS,
    GREATER_THAN,
    GREATER_THAN_OR_EQUAL,
    LESS_THAN,
    LESS_THAN_OR_EQUAL,
}