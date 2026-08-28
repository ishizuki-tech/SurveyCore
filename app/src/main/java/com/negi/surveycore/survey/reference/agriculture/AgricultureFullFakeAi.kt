package com.negi.surveycore.survey.reference.agriculture

import com.negi.surveycore.ai.fake.FakeSurveyAi
import com.negi.surveycore.survey.core.ai.SemanticValidationResult
import com.negi.surveycore.survey.core.ai.SurveyAi

/**
 * Scripted SurveyAi fixture for the complete Agriculture Survey.
 *
 * This implementation is intentionally deterministic.
 * It does not attempt to semantically understand respondent answers.
 *
 * Each question requests one clarification and then returns Valid
 * after that clarification has been answered.
 */
object AgricultureFullFakeAi {

    fun create(): SurveyAi {
        return FakeSurveyAi(
            validationHandler = { request ->
                when (request.questionId) {
                    "Q1" ->
                        validateQ1(
                            clarificationCount =
                                request.previousClarifications.size
                        )

                    "Q2" ->
                        validateQ2(
                            clarificationCount =
                                request.previousClarifications.size
                        )

                    "Q3" ->
                        validateQ3(
                            clarificationCount =
                                request.previousClarifications.size
                        )

                    "Q4" ->
                        validateQ4(
                            clarificationCount =
                                request.previousClarifications.size
                        )

                    "Q5" ->
                        validateQ5(
                            clarificationCount =
                                request.previousClarifications.size
                        )

                    "Q6" ->
                        validateQ6(
                            clarificationCount =
                                request.previousClarifications.size
                        )

                    else ->
                        SemanticValidationResult.Failed(
                            reason =
                                "Unexpected question: ${request.questionId}"
                        )
                }
            },
        )
    }

    private fun validateQ1(
        clarificationCount: Int,
    ): SemanticValidationResult {
        return if (clarificationCount == 0) {
            SemanticValidationResult.Clarify(
                question =
                    "Is that a percentage or bags per acre, and is it your average over the last three seasons?"
            )
        } else {
            SemanticValidationResult.Valid
        }
    }

    private fun validateQ2(
        clarificationCount: Int,
    ): SemanticValidationResult {
        return if (clarificationCount == 0) {
            SemanticValidationResult.Clarify(
                question =
                    "What is the maximum yield you would give up, as a percentage or bags per acre?"
            )
        } else {
            SemanticValidationResult.Valid
        }
    }

    private fun validateQ3(
        clarificationCount: Int,
    ): SemanticValidationResult {
        return if (clarificationCount == 0) {
            SemanticValidationResult.Clarify(
                question =
                    "What measurable damage level and crop stage would make you switch varieties?"
            )
        } else {
            SemanticValidationResult.Valid
        }
    }

    private fun validateQ4(
        clarificationCount: Int,
    ): SemanticValidationResult {
        return if (clarificationCount == 0) {
            SemanticValidationResult.Clarify(
                question =
                    "Can you give one measurable target you would want from the replacement variety?"
            )
        } else {
            SemanticValidationResult.Valid
        }
    }

    private fun validateQ5(
        clarificationCount: Int,
    ): SemanticValidationResult {
        return if (clarificationCount == 0) {
            SemanticValidationResult.Clarify(
                question =
                    "Can you express that minimum harvest as a percentage of your usual harvest or bags per acre?"
            )
        } else {
            SemanticValidationResult.Valid
        }
    }

    private fun validateQ6(
        clarificationCount: Int,
    ): SemanticValidationResult {
        return if (clarificationCount == 0) {
            SemanticValidationResult.Clarify(
                question =
                    "Why is drought at that crop stage especially damaging for you?"
            )
        } else {
            SemanticValidationResult.Valid
        }
    }
}