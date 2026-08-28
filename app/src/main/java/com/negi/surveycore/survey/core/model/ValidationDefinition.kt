package com.negi.surveycore.survey.core.model

/**
 * Validation rules that can be evaluated deterministically without AI.
 */
data class DeterministicValidationDefinition(
    val minNumber: Double? = null,
    val maxNumber: Double? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
)

/**
 * Validation that requires semantic understanding of a respondent answer.
 *
 * A SurveyAi implementation may use Fake AI, LiteRT-LM, llama.cpp,
 * or another model runtime to evaluate these rules.
 */
data class SemanticValidationDefinition(
    val enabled: Boolean,
    val goal: String,
    val criteria: List<String>,
    val maxClarifications: Int,
) {

    companion object {

        fun disabled(): SemanticValidationDefinition {
            return SemanticValidationDefinition(
                enabled = false,
                goal = "",
                criteria = emptyList(),
                maxClarifications = 0,
            )
        }
    }
}