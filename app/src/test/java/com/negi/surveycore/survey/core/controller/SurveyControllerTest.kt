package com.negi.surveycore.survey.core.controller

import com.negi.surveycore.ai.fake.FakeSurveyAi
import com.negi.surveycore.survey.core.ai.ResponseEvaluationRequest
import com.negi.surveycore.survey.core.ai.ResponseEvaluationResult
import com.negi.surveycore.survey.core.engine.EngineAction
import com.negi.surveycore.survey.core.engine.SurveyEngine
import com.negi.surveycore.survey.core.engine.SurveyPhase
import com.negi.surveycore.survey.core.model.AnswerDefinition
import com.negi.surveycore.survey.core.model.AnswerType
import com.negi.surveycore.survey.core.model.EndNode
import com.negi.surveycore.survey.core.model.FollowUpDefinition
import com.negi.surveycore.survey.core.model.InterviewerDefinition
import com.negi.surveycore.survey.core.model.LocalizedText
import com.negi.surveycore.survey.core.model.NavigationDefinition
import com.negi.surveycore.survey.core.model.QuestionDefinition
import com.negi.surveycore.survey.core.model.QuestionNode
import com.negi.surveycore.survey.core.model.StartNode
import com.negi.surveycore.survey.core.model.SurveyDefinition
import com.negi.surveycore.survey.core.model.SurveyFlowDefinition
import com.negi.surveycore.survey.core.model.SurveyMetadata
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration tests for the unified respondent-turn evaluation flow.
 *
 * These tests exercise SurveyController, SurveyEngine, and FakeSurveyAi
 * together without loading a real language model.
 */
class SurveyControllerTest {

    @Test
    fun responseEvaluation_followUpThenDone_advancesAndStoresCompleteHistory() =
        runBlocking {
            val requests =
                mutableListOf<ResponseEvaluationRequest>()

            val surveyAi =
                FakeSurveyAi(
                    responseEvaluationHandler = {
                            request ->

                        requests +=
                            request

                        when (
                            requests.size
                        ) {
                            1 ->
                                ResponseEvaluationResult.FollowUp(
                                    sufficiency = 35,
                                    gap =
                                        "The measurement unit is unclear.",
                                    question =
                                        "What unit does 20 represent?",
                                )

                            2 ->
                                ResponseEvaluationResult.Done(
                                    sufficiency = 100,
                                )

                            else ->
                                error(
                                    "Unexpected response evaluation call."
                                )
                        }
                    },
                )

            val controller =
                SurveyController(
                    engine =
                        SurveyEngine(
                            definition =
                                testSurveyDefinition()
                        ),
                    surveyAi =
                        surveyAi,
                )

            val startAction =
                controller.start()

            assertTrue(
                startAction is EngineAction.AskMajorQuestion
            )

            val firstQuestion =
                startAction as EngineAction.AskMajorQuestion

            assertEquals(
                "Q1",
                firstQuestion.questionId,
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
                "What unit does 20 represent?",
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

            val firstRequest =
                requests[0]

            assertEquals(
                "20",
                firstRequest.originalAnswer,
            )

            assertTrue(
                firstRequest.previousFollowUps.isEmpty()
            )

            val nextQuestionAction =
                controller.submitText(
                    "percent"
                )

            assertTrue(
                nextQuestionAction is EngineAction.AskMajorQuestion
            )

            val nextQuestion =
                nextQuestionAction as EngineAction.AskMajorQuestion

            assertEquals(
                "Q2",
                nextQuestion.questionId,
            )

            assertEquals(
                SurveyPhase.AWAITING_MAJOR_ANSWER,
                controller.state.phase,
            )

            /*
             * One model-backed SurveyAi invocation occurs for each respondent
             * turn: once for the major answer and once for the follow-up
             * answer.
             */
            assertEquals(
                2,
                requests.size,
            )

            val secondRequest =
                requests[1]

            assertEquals(
                "20",
                secondRequest.originalAnswer,
            )

            assertEquals(
                1,
                secondRequest.previousFollowUps.size,
            )

            val accumulatedExchange =
                secondRequest.previousFollowUps.single()

            assertEquals(
                "What unit does 20 represent?",
                accumulatedExchange.question,
            )

            assertEquals(
                "percent",
                accumulatedExchange.answer,
            )

            val storedRecord =
                controller
                    .session
                    .answers
                    .getValue(
                        "Q1"
                    )

            /*
             * Respondent text is preserved exactly rather than silently
             * rewritten or merged by AI.
             */
            assertEquals(
                "20",
                storedRecord.originalAnswer,
            )

            assertEquals(
                1,
                storedRecord.responseFollowUps.size,
            )

            assertEquals(
                accumulatedExchange,
                storedRecord.responseFollowUps.single(),
            )

            assertEquals(
                100,
                storedRecord.finalSufficiency,
            )

            assertTrue(
                storedRecord.clarifications.isEmpty()
            )
        }

    @Test
    fun responseEvaluation_immediateDone_advancesWithoutFollowUp() =
        runBlocking {
            val requests =
                mutableListOf<ResponseEvaluationRequest>()

            val surveyAi =
                FakeSurveyAi(
                    responseEvaluationHandler = {
                            request ->

                        requests +=
                            request

                        ResponseEvaluationResult.Done(
                            sufficiency = 95,
                        )
                    },
                )

            val controller =
                SurveyController(
                    engine =
                        SurveyEngine(
                            definition =
                                testSurveyDefinition()
                        ),
                    surveyAi =
                        surveyAi,
                )

            controller.start()

            val action =
                controller.submitText(
                    "20 percent averaged over the last three seasons"
                )

            assertTrue(
                action is EngineAction.AskMajorQuestion
            )

            val nextQuestion =
                action as EngineAction.AskMajorQuestion

            assertEquals(
                "Q2",
                nextQuestion.questionId,
            )

            assertEquals(
                1,
                requests.size,
            )

            val request =
                requests.single()

            assertEquals(
                "20 percent averaged over the last three seasons",
                request.originalAnswer,
            )

            assertTrue(
                request.previousFollowUps.isEmpty()
            )

            val storedRecord =
                controller
                    .session
                    .answers
                    .getValue(
                        "Q1"
                    )

            assertEquals(
                95,
                storedRecord.finalSufficiency,
            )

            assertTrue(
                storedRecord.responseFollowUps.isEmpty()
            )
        }

    @Test
    fun responseEvaluation_followUpLimitReached_doesNotSilentlyAcceptAnswer() =
        runBlocking {
            val requests =
                mutableListOf<ResponseEvaluationRequest>()

            val surveyAi =
                FakeSurveyAi(
                    responseEvaluationHandler = {
                            request ->

                        requests +=
                            request

                        ResponseEvaluationResult.FollowUp(
                            sufficiency =
                                if (
                                    requests.size == 1
                                ) {
                                    30
                                } else {
                                    60
                                },
                            gap =
                                if (
                                    requests.size == 1
                                ) {
                                    "The measurement unit is unclear."
                                } else {
                                    "Recent-season context is still unclear."
                                },
                            question =
                                if (
                                    requests.size == 1
                                ) {
                                    "What unit does 20 represent?"
                                } else {
                                    "Is that representative of recent seasons?"
                                },
                        )
                    },
                )

            val controller =
                SurveyController(
                    engine =
                        SurveyEngine(
                            definition =
                                testSurveyDefinition(
                                    q1MaxFollowUps =
                                        1
                                )
                        ),
                    surveyAi =
                        surveyAi,
                )

            controller.start()

            val firstFollowUp =
                controller.submitText(
                    "20"
                )

            assertTrue(
                firstFollowUp is EngineAction.AskResponseFollowUp
            )

            val exhaustedAction =
                controller.submitText(
                    "percent"
                )

            assertTrue(
                exhaustedAction is EngineAction.ResponseEvaluationExhausted
            )

            val exhausted =
                exhaustedAction as EngineAction.ResponseEvaluationExhausted

            assertEquals(
                "Q1",
                exhausted.questionId,
            )

            assertEquals(
                SurveyPhase.AWAITING_MAJOR_ANSWER,
                controller.state.phase,
            )

            /*
             * The exhausted answer must not be silently stored as accepted.
             */
            assertTrue(
                controller.session.answers.isEmpty()
            )

            assertEquals(
                2,
                requests.size,
            )
        }

    @Test
    fun responseEvaluation_blankFollowUpAnswer_isRejectedWithoutAnotherAiCall() =
        runBlocking {
            val requests =
                mutableListOf<ResponseEvaluationRequest>()

            val surveyAi =
                FakeSurveyAi(
                    responseEvaluationHandler = {
                            request ->

                        requests +=
                            request

                        ResponseEvaluationResult.FollowUp(
                            sufficiency = 35,
                            gap =
                                "The measurement unit is unclear.",
                            question =
                                "What unit does 20 represent?",
                        )
                    },
                )

            val controller =
                SurveyController(
                    engine =
                        SurveyEngine(
                            definition =
                                testSurveyDefinition()
                        ),
                    surveyAi =
                        surveyAi,
                )

            controller.start()

            val followUpAction =
                controller.submitText(
                    "20"
                )

            assertTrue(
                followUpAction is EngineAction.AskResponseFollowUp
            )

            assertEquals(
                1,
                requests.size,
            )

            val rejectedAction =
                controller.submitText(
                    "   "
                )

            assertTrue(
                rejectedAction is EngineAction.AnswerRejected
            )

            assertEquals(
                1,
                requests.size,
            )

            assertEquals(
                SurveyPhase.AWAITING_RESPONSE_FOLLOW_UP,
                controller.state.phase,
            )

            assertTrue(
                controller.session.answers.isEmpty()
            )
        }

    private fun testSurveyDefinition(
        q1MaxFollowUps: Int =
            2,
    ): SurveyDefinition {
        return SurveyDefinition(
            schemaVersion =
                2,
            metadata =
                SurveyMetadata(
                    id =
                        "response-evaluation-integration-test",
                    version =
                        1,
                    title =
                        LocalizedText(
                            default =
                                "Response Evaluation Integration Test"
                        ),
                    defaultLanguage =
                        "en",
                ),
            interviewer =
                InterviewerDefinition(
                    instruction =
                        LocalizedText(
                            default =
                                "Ask questions neutrally and professionally."
                        )
                ),
            flow =
                SurveyFlowDefinition(
                    startNodeId =
                        "Start",
                    nodes =
                        listOf(
                            StartNode(
                                id =
                                    "Start",
                                nextNodeId =
                                    "Q1",
                            ),
                            QuestionNode(
                                id =
                                    "Q1",
                                question =
                                    QuestionDefinition(
                                        prompt =
                                            LocalizedText(
                                                default =
                                                    "How much yield do you lose because of fall armyworm?"
                                            ),
                                        answer =
                                            AnswerDefinition(
                                                type =
                                                    AnswerType.TEXT,
                                            ),
                                        followUp =
                                            FollowUpDefinition(
                                                enabled =
                                                    true,
                                                maxQuestions =
                                                    q1MaxFollowUps,
                                                targets =
                                                    emptyList(),
                                                goal =
                                                    "Understand the respondent's fall armyworm yield loss " +
                                                            "well enough to know the approximate magnitude, " +
                                                            "how it is measured, and whether it is " +
                                                            "representative of recent seasons.",
                                            ),
                                    ),
                                navigation =
                                    NavigationDefinition(
                                        defaultNextNodeId =
                                            "Q2"
                                    ),
                            ),
                            QuestionNode(
                                id =
                                    "Q2",
                                question =
                                    QuestionDefinition(
                                        prompt =
                                            LocalizedText(
                                                default =
                                                    "What is your second answer?"
                                            ),
                                        answer =
                                            AnswerDefinition(
                                                type =
                                                    AnswerType.TEXT,
                                            ),
                                    ),
                                navigation =
                                    NavigationDefinition(
                                        defaultNextNodeId =
                                            "Done"
                                    ),
                            ),
                            EndNode(
                                id =
                                    "Done",
                                completionMessage =
                                    LocalizedText(
                                        default =
                                            "Survey complete."
                                    ),
                            ),
                        ),
                ),
        )
    }
}
