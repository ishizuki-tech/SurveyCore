package com.negi.surveycore.survey.core.engine

import com.negi.surveycore.survey.core.ai.SemanticValidationResult
import com.negi.surveycore.survey.reference.agriculture.AgricultureSurvey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the Agriculture Q1 semantic-validation
 * vertical slice.
 *
 * These tests exercise SurveyEngine directly and require no Android
 * runtime, LiteRT-LM, Gemma, llama.cpp, or network access.
 */
class AgricultureQ1EngineTest {

    @Test
    fun start_returnsAgricultureQ1() {
        val engine =
            createEngine()

        val action =
            engine.start()

        assertTrue(
            action is EngineAction.AskMajorQuestion
        )

        val question =
            action as EngineAction.AskMajorQuestion

        assertEquals(
            "Q1",
            question.questionId,
        )

        assertTrue(
            question.prompt.contains(
                "fall armyworm"
            )
        )

        assertEquals(
            SurveyPhase.AWAITING_MAJOR_ANSWER,
            engine.state.phase,
        )
    }

    @Test
    fun emptyRequiredAnswer_isRejected() {
        val engine =
            createStartedEngine()

        val action =
            engine.submitMajorAnswer("   ")

        assertTrue(
            action is EngineAction.AnswerRejected
        )

        assertEquals(
            SurveyPhase.AWAITING_MAJOR_ANSWER,
            engine.state.phase,
        )

        assertTrue(
            engine.session.answers.isEmpty()
        )
    }

    @Test
    fun validMajorAnswer_requestsSemanticValidation() {
        val engine =
            createStartedEngine()

        val action =
            engine.submitMajorAnswer("20")

        assertTrue(
            action is EngineAction.RequestSemanticValidation
        )

        val request =
            (
                    action as
                            EngineAction.RequestSemanticValidation
                    ).request

        assertEquals(
            "agriculture_maize",
            request.surveyId,
        )

        assertEquals(
            "Q1",
            request.questionId,
        )

        assertEquals(
            "20",
            request.originalAnswer,
        )

        assertTrue(
            request.previousClarifications.isEmpty()
        )

        assertEquals(
            SurveyPhase.VALIDATING_MAJOR_ANSWER,
            engine.state.phase,
        )
    }

    @Test
    fun immediatelyValidAnswer_isAcceptedAndSurveyCompletes() {
        val engine =
            createStartedEngine()

        engine.submitMajorAnswer(
            "About 20 percent on average over the last three seasons."
        )

        val action =
            engine.applySemanticValidation(
                SemanticValidationResult.Valid
            )

        assertTrue(
            action is EngineAction.Complete
        )

        assertEquals(
            SurveyPhase.COMPLETE,
            engine.state.phase,
        )

        val record =
            engine.session.answers["Q1"]

        checkNotNull(record)

        assertEquals(
            "About 20 percent on average over the last three seasons.",
            record.originalAnswer,
        )

        assertTrue(
            record.clarifications.isEmpty()
        )
    }

    @Test
    fun oneClarification_isStoredAndPassedBackForRevalidation() {
        val engine =
            createStartedEngine()

        engine.submitMajorAnswer("20")

        val clarificationAction =
            engine.applySemanticValidation(
                SemanticValidationResult.Clarify(
                    question =
                        "Is that 20 percent or 20 bags per acre?"
                )
            )

        assertTrue(
            clarificationAction is
                    EngineAction.AskClarification
        )

        assertEquals(
            SurveyPhase.AWAITING_VALIDATION_CLARIFICATION,
            engine.state.phase,
        )

        val revalidationAction =
            engine.submitClarificationAnswer(
                "20 percent"
            )

        assertTrue(
            revalidationAction is
                    EngineAction.RequestSemanticValidation
        )

        val request =
            (
                    revalidationAction as
                            EngineAction.RequestSemanticValidation
                    ).request

        assertEquals(
            "20",
            request.originalAnswer,
        )

        assertEquals(
            1,
            request.previousClarifications.size,
        )

        assertEquals(
            "Is that 20 percent or 20 bags per acre?",
            request.previousClarifications[0].question,
        )

        assertEquals(
            "20 percent",
            request.previousClarifications[0].answer,
        )
    }

    @Test
    fun twoClarifications_thenValid_preservesOriginalAndHistory() {
        val engine =
            createStartedEngine()

        engine.submitMajorAnswer("20")

        engine.applySemanticValidation(
            SemanticValidationResult.Clarify(
                question =
                    "Is that 20 percent or 20 bags per acre?"
            )
        )

        engine.submitClarificationAnswer(
            "20 percent"
        )

        engine.applySemanticValidation(
            SemanticValidationResult.Clarify(
                question =
                    "Is that an average over the last three seasons?"
            )
        )

        val revalidationAction =
            engine.submitClarificationAnswer(
                "Yes, about 20 percent on average."
            )

        assertTrue(
            revalidationAction is
                    EngineAction.RequestSemanticValidation
        )

        val request =
            (
                    revalidationAction as
                            EngineAction.RequestSemanticValidation
                    ).request

        assertEquals(
            2,
            request.previousClarifications.size,
        )

        val completeAction =
            engine.applySemanticValidation(
                SemanticValidationResult.Valid
            )

        assertTrue(
            completeAction is EngineAction.Complete
        )

        assertEquals(
            SurveyPhase.COMPLETE,
            engine.state.phase,
        )

        val record =
            engine.session.answers["Q1"]

        checkNotNull(record)

        /*
         * The original respondent answer must remain unchanged.
         * Clarifications are preserved separately.
         */
        assertEquals(
            "20",
            record.originalAnswer,
        )

        assertEquals(
            2,
            record.clarifications.size,
        )

        assertEquals(
            "20 percent",
            record.clarifications[0].answer,
        )

        assertEquals(
            "Yes, about 20 percent on average.",
            record.clarifications[1].answer,
        )
    }

    @Test
    fun clarificationLimitExceeded_doesNotAcceptAnswer() {
        val engine =
            createStartedEngine()

        engine.submitMajorAnswer("20")

        engine.applySemanticValidation(
            SemanticValidationResult.Clarify(
                question =
                    "Is that 20 percent or 20 bags per acre?"
            )
        )

        engine.submitClarificationAnswer(
            "20 percent"
        )

        engine.applySemanticValidation(
            SemanticValidationResult.Clarify(
                question =
                    "Is that an average over the last three seasons?"
            )
        )

        engine.submitClarificationAnswer(
            "I am not sure."
        )

        val action =
            engine.applySemanticValidation(
                SemanticValidationResult.Clarify(
                    question =
                        "Can you clarify further?"
                )
            )

        assertTrue(
            action is
                    EngineAction.SemanticValidationExhausted
        )

        /*
         * Exhausting the AI clarification budget must never be
         * interpreted as successful validation.
         */
        assertEquals(
            SurveyPhase.AWAITING_MAJOR_ANSWER,
            engine.state.phase,
        )

        assertTrue(
            engine.session.answers.isEmpty()
        )
    }

    @Test
    fun aiFailure_doesNotAcceptAnswer() {
        val engine =
            createStartedEngine()

        engine.submitMajorAnswer("20")

        val action =
            engine.applySemanticValidation(
                SemanticValidationResult.Failed(
                    reason =
                        "Model output could not be parsed."
                )
            )

        assertTrue(
            action is
                    EngineAction.AiEvaluationFailed
        )

        assertTrue(
            engine.session.answers.isEmpty()
        )

        assertEquals(
            SurveyPhase.VALIDATING_MAJOR_ANSWER,
            engine.state.phase,
        )
    }

    @Test
    fun acceptedAnswer_completionMessageIsReturned() {
        val engine =
            createStartedEngine()

        engine.submitMajorAnswer(
            "20 percent average over the last three seasons"
        )

        val action =
            engine.applySemanticValidation(
                SemanticValidationResult.Valid
            )

        assertTrue(
            action is EngineAction.Complete
        )

        val complete =
            action as EngineAction.Complete

        assertEquals(
            "Thank you. The survey is complete.",
            complete.completionMessage,
        )
    }

    private fun createEngine(): SurveyEngine {
        return SurveyEngine(
            definition =
                AgricultureSurvey
                    .createQ1ReferenceSurvey()
        )
    }

    private fun createStartedEngine(): SurveyEngine {
        return createEngine().also {
            it.start()
        }
    }
}