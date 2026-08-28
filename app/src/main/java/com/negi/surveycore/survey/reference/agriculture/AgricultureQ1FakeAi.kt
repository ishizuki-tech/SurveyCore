package com.negi.surveycore.survey.reference.agriculture

import com.negi.surveycore.ai.fake.FakeSurveyAi
import com.negi.surveycore.survey.core.ai.SemanticValidationResult
import com.negi.surveycore.survey.core.ai.SurveyAi

/**
 * Scripted AI fixture for the Agriculture Q1 vertical slice.
 *
 * This fixture does not attempt to understand the respondent's answer.
 * It returns deterministic responses based only on the accumulated
 * clarification history.
 */
object AgricultureQ1FakeAi {

    fun create(): SurveyAi {
        return FakeSurveyAi(
            validationHandler = { request ->

                if (request.questionId != "Q1") {
                    SemanticValidationResult.Failed(
                        reason =
                            "Unexpected question: ${request.questionId}"
                    )
                } else {
                    when (request.previousClarifications.size) {
                        0 ->
                            SemanticValidationResult.Clarify(
                                question =
                                    "Is that 20 percent or 20 bags per acre?"
                            )

                        1 ->
                            SemanticValidationResult.Clarify(
                                question =
                                    "Is that an average over the last three seasons?"
                            )

                        else ->
                            SemanticValidationResult.Valid
                    }
                }
            },
        )
    }
}