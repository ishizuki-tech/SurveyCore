package com.negi.surveycore.survey.core.controller

import com.negi.surveycore.survey.core.ai.SurveyAi
import com.negi.surveycore.survey.core.engine.EngineAction
import com.negi.surveycore.survey.core.engine.SurveyEngine
import com.negi.surveycore.survey.core.engine.SurveyPhase
import com.negi.surveycore.survey.core.engine.SurveySession
import com.negi.surveycore.survey.core.engine.SurveyState

/**
 * Coordinates SurveyEngine with an external SurveyAi implementation.
 *
 * SurveyEngine remains completely deterministic and does not invoke AI
 * directly. The controller resolves internal AI requests and returns only
 * the next externally visible engine action.
 */
class SurveyController(
    private val engine: SurveyEngine,
    private val surveyAi: SurveyAi,
) {

    val state: SurveyState
        get() = engine.state

    val session: SurveySession
        get() = engine.session

    suspend fun start(): EngineAction {
        return resolve(
            engine.start()
        )
    }

    suspend fun submitText(
        answer: String,
    ): EngineAction {
        val action =
            when (engine.state.phase) {
                SurveyPhase.AWAITING_MAJOR_ANSWER ->
                    engine.submitMajorAnswer(answer)

                SurveyPhase.AWAITING_RESPONSE_FOLLOW_UP ->
                    engine.submitResponseFollowUpAnswer(answer)

                SurveyPhase.AWAITING_VALIDATION_CLARIFICATION ->
                    engine.submitClarificationAnswer(answer)

                else ->
                    error(
                        "Survey is not waiting for respondent input. " +
                                "Current phase: ${engine.state.phase}"
                    )
            }

        return resolve(action)
    }

    /**
     * Resolves internal AI requests until SurveyEngine produces an
     * externally visible action.
     *
     * Each RequestResponseEvaluation action causes exactly one call to
     * SurveyAi.evaluateResponse().
     */
    private suspend fun resolve(
        initialAction: EngineAction,
    ): EngineAction {
        var action =
            initialAction

        while (true) {
            action =
                when (action) {
                    is EngineAction.RequestResponseEvaluation -> {
                        val result =
                            surveyAi.evaluateResponse(
                                action.request
                            )

                        engine.applyResponseEvaluation(
                            result
                        )
                    }

                    is EngineAction.RequestSemanticValidation -> {
                        val result =
                            surveyAi.validateAnswer(
                                action.request
                            )

                        engine.applySemanticValidation(
                            result
                        )
                    }

                    else ->
                        return action
                }
        }
    }
}
