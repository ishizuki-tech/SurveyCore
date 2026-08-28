package com.negi.surveycore.survey.core.model

/**
 * Supported structured answer types.
 */
enum class AnswerType {
    TEXT,
    INTEGER,
    DECIMAL,
    BOOLEAN,
    SINGLE_CHOICE,
    MULTI_CHOICE,
}

/**
 * Supported respondent input mechanisms.
 *
 * Voice input is converted to text outside Survey Core.
 */
enum class InputMode {
    TEXT,
    VOICE,
}

data class AnswerOption(
    val id: String,
    val label: LocalizedText,
)

/**
 * Defines the expected answer and its validation behavior.
 */
data class AnswerDefinition(
    val type: AnswerType,
    val required: Boolean = true,
    val inputModes: Set<InputMode> = setOf(InputMode.TEXT),
    val options: List<AnswerOption> = emptyList(),
    val deterministicValidation: DeterministicValidationDefinition =
        DeterministicValidationDefinition(),
    val semanticValidation: SemanticValidationDefinition =
        SemanticValidationDefinition.disabled(),
)