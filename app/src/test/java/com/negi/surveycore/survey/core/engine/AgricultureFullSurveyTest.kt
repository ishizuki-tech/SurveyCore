package com.negi.surveycore.survey.core.engine

import com.negi.surveycore.survey.core.controller.SurveyController
import com.negi.surveycore.survey.reference.agriculture.AgricultureFullFakeAi
import com.negi.surveycore.survey.reference.agriculture.AgricultureSurvey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end regression test for the complete Agriculture Survey.
 *
 * This test runs Q1 through Q6 using the real SurveyEngine and
 * SurveyController, but uses FakeSurveyAi instead of a real language model.
 *
 * No Android runtime, LiteRT-LM, Gemma, llama.cpp, or network access
 * is required.
 */
class AgricultureFullSurveyTest {

    @Test
    fun fullAgricultureSurvey_completesAllSixQuestions() =
        runBlocking {
            val controller =
                createController()

            var action =
                controller.start()

            assertMajorQuestion(
                action = action,
                expectedQuestionId = "Q1",
            )

            // Q1
            action =
                controller.submitText(
                    "About 20."
                )

            assertClarification(
                action = action,
                expectedQuestionId = "Q1",
            )

            action =
                controller.submitText(
                    "About 20 percent on average over the last three seasons."
                )

            assertMajorQuestion(
                action = action,
                expectedQuestionId = "Q2",
            )

            // Q2
            action =
                controller.submitText(
                    "I could give up some yield."
                )

            assertClarification(
                action = action,
                expectedQuestionId = "Q2",
            )

            action =
                controller.submitText(
                    "At most 10 percent."
                )

            assertMajorQuestion(
                action = action,
                expectedQuestionId = "Q3",
            )

            // Q3
            action =
                controller.submitText(
                    "When the damage gets bad."
                )

            assertClarification(
                action = action,
                expectedQuestionId = "Q3",
            )

            action =
                controller.submitText(
                    "Around 25 percent of plants affected during tasseling."
                )

            assertMajorQuestion(
                action = action,
                expectedQuestionId = "Q4",
            )

            // Q4
            action =
                controller.submitText(
                    "I want a better replacement variety."
                )

            assertClarification(
                action = action,
                expectedQuestionId = "Q4",
            )

            action =
                controller.submitText(
                    "I would want maturity within about 100 days."
                )

            assertMajorQuestion(
                action = action,
                expectedQuestionId = "Q5",
            )

            // Q5
            action =
                controller.submitText(
                    "I would still plant it after a fairly poor harvest."
                )

            assertClarification(
                action = action,
                expectedQuestionId = "Q5",
            )

            action =
                controller.submitText(
                    "At least 60 percent of my usual harvest."
                )

            assertMajorQuestion(
                action = action,
                expectedQuestionId = "Q6",
            )

            // Q6
            action =
                controller.submitText(
                    "During tasseling and silking."
                )

            assertClarification(
                action = action,
                expectedQuestionId = "Q6",
            )

            action =
                controller.submitText(
                    "Because poor moisture then reduces pollination and grain formation."
                )

            assertTrue(
                action is EngineAction.Complete
            )

            assertEquals(
                SurveyPhase.COMPLETE,
                controller.state.phase,
            )

            /*
             * The complete reference survey must store one accepted
             * MajorAnswerRecord for every agriculture question.
             */
            assertEquals(
                6,
                controller.session.answers.size,
            )

            for (questionNumber in 1..6) {
                val questionId =
                    "Q$questionNumber"

                assertNotNull(
                    "Missing stored answer for $questionId",
                    controller.session.answers[questionId],
                )
            }
        }

    @Test
    fun fullAgricultureSurvey_preservesOriginalAnswersAndClarifications() =
        runBlocking {
            val controller =
                createController()

            controller.start()

            controller.submitText("20")

            controller.submitText(
                "20 percent average over the last three seasons."
            )

            controller.submitText(
                "Some yield."
            )

            controller.submitText(
                "10 percent."
            )

            controller.submitText(
                "Bad damage."
            )

            controller.submitText(
                "25 percent of plants during tasseling."
            )

            controller.submitText(
                "Better performance."
            )

            controller.submitText(
                "Maturity in about 100 days."
            )

            controller.submitText(
                "A poor harvest."
            )

            controller.submitText(
                "60 percent of my normal harvest."
            )

            controller.submitText(
                "Tasseling."
            )

            val finalAction =
                controller.submitText(
                    "Because drought then reduces pollination."
                )

            assertTrue(
                finalAction is EngineAction.Complete
            )

            val q1 =
                controller.session.answers["Q1"]

            checkNotNull(q1)

            /*
             * The respondent's first answer must remain unchanged.
             * AI clarification evidence is stored separately.
             */
            assertEquals(
                "20",
                q1.originalAnswer,
            )

            assertEquals(
                1,
                q1.clarifications.size,
            )

            assertEquals(
                "20 percent average over the last three seasons.",
                q1.clarifications[0].answer,
            )

            val q6 =
                controller.session.answers["Q6"]

            checkNotNull(q6)

            assertEquals(
                "Tasseling.",
                q6.originalAnswer,
            )

            assertEquals(
                1,
                q6.clarifications.size,
            )

            assertEquals(
                "Because drought then reduces pollination.",
                q6.clarifications[0].answer,
            )
        }

    private fun createController(): SurveyController {
        return SurveyController(
            engine =
                SurveyEngine(
                    definition =
                        AgricultureSurvey
                            .createFullReferenceSurvey()
                ),

            surveyAi =
                AgricultureFullFakeAi.create(),
        )
    }

    private fun assertMajorQuestion(
        action: EngineAction,
        expectedQuestionId: String,
    ) {
        assertTrue(
            "Expected AskMajorQuestion but was $action",
            action is EngineAction.AskMajorQuestion,
        )

        val majorQuestion =
            action as EngineAction.AskMajorQuestion

        assertEquals(
            expectedQuestionId,
            majorQuestion.questionId,
        )
    }

    private fun assertClarification(
        action: EngineAction,
        expectedQuestionId: String,
    ) {
        assertTrue(
            "Expected AskClarification but was $action",
            action is EngineAction.AskClarification,
        )

        val clarification =
            action as EngineAction.AskClarification

        assertEquals(
            expectedQuestionId,
            clarification.questionId,
        )

        assertTrue(
            clarification.question.isNotBlank()
        )
    }
}