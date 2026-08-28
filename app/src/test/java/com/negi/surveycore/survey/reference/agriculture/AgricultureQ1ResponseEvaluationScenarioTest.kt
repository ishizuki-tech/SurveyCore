package com.negi.surveycore.survey.reference.agriculture

import com.negi.surveycore.ai.fake.FakeSurveyAi
import com.negi.surveycore.survey.core.ai.ResponseEvaluationRequest
import com.negi.surveycore.survey.core.ai.ResponseEvaluationResult
import com.negi.surveycore.survey.core.controller.SurveyController
import com.negi.surveycore.survey.core.engine.EngineAction
import com.negi.surveycore.survey.core.engine.SurveyEngine
import com.negi.surveycore.survey.core.engine.SurveyPhase
import com.negi.surveycore.survey.core.model.SurveyDefinition
import com.negi.surveycore.survey.yaml.SurveyValidator
import com.negi.surveycore.survey.yaml.SurveyYamlLoader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product-level contract tests for the Agriculture Q1 response-evaluation
 * scenario.
 *
 * These tests intentionally use FakeSurveyAi. They verify that the real
 * Agriculture YAML, SurveyController, SurveyEngine, accumulated follow-up
 * history, and session storage behave correctly for the expected interviewer
 * decisions.
 *
 * They do not verify that a specific language model will produce those
 * decisions. Qwen and Gemma model quality remains a separate device-level
 * evaluation.
 */
class AgricultureQ1ResponseEvaluationScenarioTest {

    @Test
    fun q1_bareNumber_requiresUnitFollowUp_thenUnitAnswerCompletesQ1() =
        runBlocking {
            val requests =
                mutableListOf<ResponseEvaluationRequest>()

            val surveyAi =
                FakeSurveyAi(
                    responseEvaluationHandler = {
                            request ->

                        requests +=
                            request

                        when (requests.size) {
                            1 -> {
                                assertQ1GoalContract(
                                    request
                                )

                                assertEquals(
                                    "20",
                                    request.originalAnswer,
                                )

                                assertTrue(
                                    request.previousFollowUps.isEmpty()
                                )

                                ResponseEvaluationResult.FollowUp(
                                    sufficiency = 20,
                                    gap =
                                        "The measurement unit is ambiguous.",
                                    question =
                                        "Percent or bags per acre?",
                                )
                            }

                            2 -> {
                                assertQ1GoalContract(
                                    request
                                )

                                assertEquals(
                                    "20",
                                    request.originalAnswer,
                                )

                                assertEquals(
                                    1,
                                    request.previousFollowUps.size,
                                )

                                val exchange =
                                    request.previousFollowUps.single()

                                assertEquals(
                                    "Percent or bags per acre?",
                                    exchange.question,
                                )

                                assertEquals(
                                    "percent",
                                    exchange.answer,
                                )

                                ResponseEvaluationResult.Done(
                                    sufficiency = 81,
                                )
                            }

                            else ->
                                error(
                                    "Unexpected response evaluation call ${requests.size}."
                                )
                        }
                    },
                )

            val controller =
                createController(
                    surveyAi =
                        surveyAi
                )

            val startAction =
                controller.start()

            assertTrue(
                startAction is EngineAction.AskMajorQuestion
            )

            assertEquals(
                "Q1",
                (startAction as EngineAction.AskMajorQuestion).questionId,
            )

            val followUpAction =
                controller.submitText(
                    "20"
                )

            assertTrue(
                followUpAction is EngineAction.AskResponseFollowUp
            )

            val followUp =
                followUpAction as EngineAction.AskResponseFollowUp

            assertEquals(
                "Q1",
                followUp.questionId,
            )

            assertEquals(
                "Percent or bags per acre?",
                followUp.question,
            )

            assertEquals(
                SurveyPhase.AWAITING_RESPONSE_FOLLOW_UP,
                controller.state.phase,
            )

            val nextQuestionAction =
                controller.submitText(
                    "percent"
                )

            assertTrue(
                nextQuestionAction is EngineAction.AskMajorQuestion
            )

            assertEquals(
                "Q2",
                (nextQuestionAction as EngineAction.AskMajorQuestion).questionId,
            )

            assertEquals(
                SurveyPhase.AWAITING_MAJOR_ANSWER,
                controller.state.phase,
            )

            assertEquals(
                2,
                requests.size,
            )

            val storedRecord =
                controller
                    .session
                    .answers
                    .getValue(
                        "Q1"
                    )

            assertEquals(
                "20",
                storedRecord.originalAnswer,
            )

            assertEquals(
                1,
                storedRecord.responseFollowUps.size,
            )

            val storedExchange =
                storedRecord.responseFollowUps.single()

            assertEquals(
                "Percent or bags per acre?",
                storedExchange.question,
            )

            assertEquals(
                "percent",
                storedExchange.answer,
            )

            assertEquals(
                81,
                storedRecord.finalSufficiency,
            )
        }

    @Test
    fun q1_numberWithUnit_canCompleteWithoutFollowUp() =
        runBlocking {
            val requests =
                mutableListOf<ResponseEvaluationRequest>()

            val surveyAi =
                FakeSurveyAi(
                    responseEvaluationHandler = {
                            request ->

                        requests +=
                            request

                        assertQ1GoalContract(
                            request
                        )

                        assertEquals(
                            "20 percent",
                            request.originalAnswer,
                        )

                        assertTrue(
                            request.previousFollowUps.isEmpty()
                        )

                        ResponseEvaluationResult.Done(
                            sufficiency = 81,
                        )
                    },
                )

            val controller =
                createController(
                    surveyAi =
                        surveyAi
                )

            controller.start()

            val action =
                controller.submitText(
                    "20 percent"
                )

            assertTrue(
                action is EngineAction.AskMajorQuestion
            )

            assertEquals(
                "Q2",
                (action as EngineAction.AskMajorQuestion).questionId,
            )

            assertEquals(
                1,
                requests.size,
            )

            val storedRecord =
                controller
                    .session
                    .answers
                    .getValue(
                        "Q1"
                    )

            assertEquals(
                "20 percent",
                storedRecord.originalAnswer,
            )

            assertTrue(
                storedRecord.responseFollowUps.isEmpty()
            )

            assertEquals(
                81,
                storedRecord.finalSufficiency,
            )
        }

    @Test
    fun q1_dontKnow_requiresFollowUp() =
        runBlocking {
            val requests =
                mutableListOf<ResponseEvaluationRequest>()

            val surveyAi =
                FakeSurveyAi(
                    responseEvaluationHandler = {
                            request ->

                        requests +=
                            request

                        assertQ1GoalContract(
                            request
                        )

                        assertEquals(
                            "I don't know",
                            request.originalAnswer,
                        )

                        assertTrue(
                            request.previousFollowUps.isEmpty()
                        )

                        ResponseEvaluationResult.FollowUp(
                            sufficiency = 0,
                            gap =
                                "No yield-loss estimate was provided.",
                            question =
                                "What is your best estimate of the yield loss?",
                        )
                    },
                )

            val controller =
                createController(
                    surveyAi =
                        surveyAi
                )

            controller.start()

            val action =
                controller.submitText(
                    "I don't know"
                )

            assertTrue(
                action is EngineAction.AskResponseFollowUp
            )

            val followUp =
                action as EngineAction.AskResponseFollowUp

            assertEquals(
                "Q1",
                followUp.questionId,
            )

            assertEquals(
                "What is your best estimate of the yield loss?",
                followUp.question,
            )

            assertEquals(
                SurveyPhase.AWAITING_RESPONSE_FOLLOW_UP,
                controller.state.phase,
            )

            assertEquals(
                1,
                requests.size,
            )

            /*
             * Q1 is not accepted until the response evaluation returns DONE.
             */
            assertTrue(
                controller.session.answers.isEmpty()
            )
        }

    private fun createController(
        surveyAi: FakeSurveyAi,
    ): SurveyController {
        val definition =
            loadAgricultureSurvey()

        SurveyValidator.requireValid(
            definition
        )

        return SurveyController(
            engine =
                SurveyEngine(
                    definition =
                        definition,
                ),
            surveyAi =
                surveyAi,
        )
    }

    /**
     * Guards the Q1 authoring contract used by the response-evaluation
     * scenarios. The survey goal defines the semantic requirement while the
     * generic evaluator decides whether the accumulated response satisfies it.
     */
    private fun assertQ1GoalContract(
        request: ResponseEvaluationRequest,
    ) {
        assertEquals(
            "Q1",
            request.questionId,
        )

        assertTrue(
            request.question.contains(
                "fall armyworm",
                ignoreCase = true,
            )
        )

        assertTrue(
            request.question.contains(
                "Percent or bags per acre are fine",
                ignoreCase = true,
            )
        )

        assertTrue(
            request.interviewGoal.contains(
                "measurement unit",
                ignoreCase = true,
            )
        )

        assertTrue(
            request.interviewGoal.contains(
                "gives only a number",
                ignoreCase = true,
            )
        )

        assertTrue(
            request.interviewGoal.contains(
                "not yet sufficient",
                ignoreCase = true,
            )
        )

        assertTrue(
            request.interviewGoal.contains(
                "which unit",
                ignoreCase = true,
            )
        )
    }

    /**
     * Loads the production Agriculture YAML rather than a synthetic test
     * definition so this scenario also detects YAML authoring regressions.
     */
    private fun loadAgricultureSurvey():
            SurveyDefinition {
        val relativePath =
            "src/main/assets/surveys/agriculture_maize_v2.yaml"

        val candidates =
            listOf(
                File(
                    relativePath
                ),
                File(
                    "app/$relativePath"
                ),
            )

        val yamlFile =
            candidates.firstOrNull {
                it.isFile
            }
                ?: error(
                    buildString {
                        appendLine(
                            "Agriculture YAML file was not found."
                        )

                        appendLine(
                            "Working directory: ${File(".").absolutePath}"
                        )

                        appendLine(
                            "Checked:"
                        )

                        candidates.forEach {
                            appendLine(
                                "- ${it.absolutePath}"
                            )
                        }
                    }
                )

        return yamlFile
            .inputStream()
            .use {
                SurveyYamlLoader.load(
                    it
                )
            }
    }
}
