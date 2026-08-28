package com.negi.surveycore.ai.model

import com.negi.surveycore.ai.backend.fake.FakeTextGenerationBackend
import com.negi.surveycore.survey.core.ai.ClarificationExchange
import com.negi.surveycore.survey.core.ai.FollowUpEvaluationRequest
import com.negi.surveycore.survey.core.ai.FollowUpEvaluationResult
import com.negi.surveycore.survey.core.ai.ResponseEvaluationRequest
import com.negi.surveycore.survey.core.ai.ResponseEvaluationResult
import com.negi.surveycore.survey.core.ai.ResponseFollowUpExchange
import com.negi.surveycore.survey.core.ai.SemanticValidationRequest
import com.negi.surveycore.survey.core.ai.SemanticValidationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSurveyAiTest {

    @Test
    fun allGroundedCriteria_returnsSemanticValid() =
        runBlocking {
            val backend =
                FakeTextGenerationBackend(
                    responses =
                        listOf(
                            "SUPPORTED: 20",
                            "SUPPORTED: percent",
                            "SUPPORTED: averaged over the last three seasons",
                        )
                )

            val surveyAi =
                ModelSurveyAi(
                    backend
                )

            val result =
                surveyAi.validateAnswer(
                    semanticRequest()
                )

            assertTrue(
                result is SemanticValidationResult.Valid
            )

            assertEquals(
                1,
                backend.initializeCallCount,
            )

            assertEquals(
                3,
                backend.generateCallCount,
            )

            assertTrue(
                backend.isReady()
            )
        }

    @Test
    fun firstMissingCriterion_generatesClarificationAndStops() =
        runBlocking {
            val backend =
                FakeTextGenerationBackend(
                    responses =
                        listOf(
                            "SUPPORTED: 20",
                            "MISSING",
                            "CLARIFY: What unit does 20 use?",
                        )
                )

            val surveyAi =
                ModelSurveyAi(
                    backend
                )

            val result =
                surveyAi.validateAnswer(
                    semanticRequest(
                        originalAnswer =
                            "20"
                    )
                )

            assertTrue(
                result is SemanticValidationResult.Clarify
            )

            val clarify =
                result as SemanticValidationResult.Clarify

            assertEquals(
                "What unit does 20 use?",
                clarify.question,
            )

            /*
             * One supported criterion, one missing criterion, and one
             * clarification generation.
             */
            assertEquals(
                3,
                backend.generateCallCount,
            )
        }

    @Test
    fun ungroundedSupportedQuote_isTreatedAsMissing() =
        runBlocking {
            val backend =
                FakeTextGenerationBackend(
                    responses =
                        listOf(
                            "SUPPORTED: 20",
                            /*
                             * "percent" appears in survey context but not in
                             * respondent evidence and must therefore fail the
                             * deterministic grounding check.
                             */
                            "SUPPORTED: percent",
                            "CLARIFY: What unit does 20 use?",
                        )
                )

            val surveyAi =
                ModelSurveyAi(
                    backend
                )

            val result =
                surveyAi.validateAnswer(
                    semanticRequest(
                        originalAnswer =
                            "20"
                    )
                )

            assertTrue(
                result is SemanticValidationResult.Clarify
            )

            val clarify =
                result as SemanticValidationResult.Clarify

            assertEquals(
                "What unit does 20 use?",
                clarify.question,
            )

            assertEquals(
                3,
                backend.generateCallCount,
            )
        }

    @Test
    fun malformedCriterionOutput_returnsFailure() =
        runBlocking {
            val backend =
                FakeTextGenerationBackend(
                    responses =
                        listOf(
                            "YES"
                        )
                )

            val surveyAi =
                ModelSurveyAi(
                    backend
                )

            val result =
                surveyAi.validateAnswer(
                    semanticRequest()
                )

            assertTrue(
                result is SemanticValidationResult.Failed
            )

            val failed =
                result as SemanticValidationResult.Failed

            assertTrue(
                failed.reason.contains(
                    "Unexpected criterion evaluation output"
                )
            )

            assertEquals(
                1,
                backend.generateCallCount,
            )
        }

    @Test
    fun supportedWithoutEvidenceQuote_returnsFailure() =
        runBlocking {
            val backend =
                FakeTextGenerationBackend(
                    responses =
                        listOf(
                            "SUPPORTED:"
                        )
                )

            val surveyAi =
                ModelSurveyAi(
                    backend
                )

            val result =
                surveyAi.validateAnswer(
                    semanticRequest()
                )

            assertTrue(
                result is SemanticValidationResult.Failed
            )

            val failed =
                result as SemanticValidationResult.Failed

            assertTrue(
                failed.reason.contains(
                    "SUPPORTED without an evidence quote"
                )
            )
        }

    @Test
    fun cumulativeClarificationEvidence_isGrounded() =
        runBlocking {
            val backend =
                FakeTextGenerationBackend(
                    responses =
                        listOf(
                            "SUPPORTED: 20",
                            "SUPPORTED: percent",
                            "MISSING",
                            "CLARIFY: Is 20 percent your average over the last three seasons?",
                        )
                )

            val surveyAi =
                ModelSurveyAi(
                    backend
                )

            val request =
                semanticRequest(
                    originalAnswer =
                        "20",
                    previousClarifications =
                        listOf(
                            ClarificationExchange(
                                question =
                                    "What unit does 20 use?",
                                answer =
                                    "percent",
                            )
                        ),
                )

            val result =
                surveyAi.validateAnswer(
                    request
                )

            assertTrue(
                result is SemanticValidationResult.Clarify
            )

            assertEquals(
                4,
                backend.generateCallCount,
            )
        }

    @Test
    fun yesAnswerInPairedClarification_canBeGrounded() =
        runBlocking {
            val backend =
                FakeTextGenerationBackend(
                    responses =
                        listOf(
                            "SUPPORTED: 20",
                            "SUPPORTED: percent",
                            "SUPPORTED: Yes",
                        )
                )

            val surveyAi =
                ModelSurveyAi(
                    backend
                )

            val request =
                semanticRequest(
                    originalAnswer =
                        "20",
                    previousClarifications =
                        listOf(
                            ClarificationExchange(
                                question =
                                    "What unit does 20 use?",
                                answer =
                                    "percent",
                            ),
                            ClarificationExchange(
                                question =
                                    "Is 20 percent your average over the " +
                                            "last three seasons?",
                                answer =
                                    "Yes",
                            ),
                        ),
                )

            val result =
                surveyAi.validateAnswer(
                    request
                )

            assertTrue(
                result is SemanticValidationResult.Valid
            )

            assertEquals(
                3,
                backend.generateCallCount,
            )
        }

    @Test
    fun criterionPrompt_containsCurrentContextButNotOtherCriteria() =
        runBlocking {
            val backend =
                FakeTextGenerationBackend(
                    responses =
                        listOf(
                            "MISSING",
                            "CLARIFY: Please provide the yield-loss magnitude.",
                        )
                )

            val surveyAi =
                ModelSurveyAi(
                    backend
                )

            surveyAi.validateAnswer(
                semanticRequest(
                    originalAnswer =
                        "unknown"
                )
            )

            val criterionRequest =
                backend.requests.first()

            assertTrue(
                criterionRequest.prompt.contains(
                    "How much yield do you lose because of fall armyworm?"
                )
            )

            assertTrue(
                criterionRequest.prompt.contains(
                    "The respondent explicitly provides a yield-loss magnitude."
                )
            )

            assertFalse(
                criterionRequest.prompt.contains(
                    "unit for the yield-loss magnitude"
                )
            )

            assertFalse(
                criterionRequest.prompt.contains(
                    "last three seasons or an average across those seasons"
                )
            )

            assertFalse(
                criterionRequest.prompt.contains(
                    "Obtain the respondent's average fall armyworm yield loss"
                )
            )

            assertTrue(
                criterionRequest.systemInstruction.contains(
                    "SUPPORTED:"
                )
            )

            assertTrue(
                criterionRequest.systemInstruction.contains(
                    "MISSING"
                )
            )
        }

    @Test
    fun noExplicitCriteria_usesValidationGoalAsSingleCriterion() =
        runBlocking {
            val backend =
                FakeTextGenerationBackend(
                    responses =
                        listOf(
                            "SUPPORTED: 20 percent"
                        )
                )

            val surveyAi =
                ModelSurveyAi(
                    backend
                )

            val result =
                surveyAi.validateAnswer(
                    semanticRequest(
                        criteria =
                            emptyList()
                    )
                )

            assertTrue(
                result is SemanticValidationResult.Valid
            )

            assertEquals(
                1,
                backend.generateCallCount,
            )

            assertTrue(
                backend.requests
                    .single()
                    .prompt
                    .contains(
                        "average fall armyworm yield loss"
                    )
            )
        }

    @Test
    fun satisfiedOutput_returnsFollowUpSatisfied() =
        runBlocking {
            val backend =
                FakeTextGenerationBackend(
                    responses =
                        listOf(
                            "SATISFIED"
                        )
                )

            val surveyAi =
                ModelSurveyAi(
                    backend
                )

            val result =
                surveyAi.evaluateFollowUp(
                    followUpRequest()
                )

            assertTrue(
                result is FollowUpEvaluationResult.Satisfied
            )
        }

    @Test
    fun followUpOutput_returnsFollowUpQuestion() =
        runBlocking {
            val backend =
                FakeTextGenerationBackend(
                    responses =
                        listOf(
                            "FOLLOW_UP: What happened in the most recent season?"
                        )
                )

            val surveyAi =
                ModelSurveyAi(
                    backend
                )

            val result =
                surveyAi.evaluateFollowUp(
                    followUpRequest()
                )

            assertTrue(
                result is FollowUpEvaluationResult.Ask
            )

            val ask =
                result as FollowUpEvaluationResult.Ask

            assertEquals(
                "What happened in the most recent season?",
                ask.question,
            )
        }

    @Test
    fun responseEvaluation_done_usesExactlyOneGeneration() =
        runBlocking {
            val backend =
                FakeTextGenerationBackend(
                    responses =
                        listOf(
                            """
                            REMAINING_GAP: NONE
                            STATUS: DONE
                            QUESTION: NONE
                            SUFFICIENCY: 95
                            """.trimIndent()
                        )
                )

            val surveyAi =
                ModelSurveyAi(
                    backend
                )

            val result =
                surveyAi.evaluateResponse(
                    responseEvaluationRequest(
                        originalAnswer =
                            "20 percent, averaged over the last three seasons"
                    )
                )

            assertEquals(
                ResponseEvaluationResult.Done(
                    sufficiency =
                        95
                ),
                result,
            )

            assertEquals(
                1,
                backend.initializeCallCount,
            )

            /*
             * One respondent turn must produce exactly one model generation.
             */
            assertEquals(
                1,
                backend.generateCallCount,
            )
        }

    @Test
    fun responseEvaluation_followUp_usesExactlyOneGeneration() =
        runBlocking {
            val backend =
                FakeTextGenerationBackend(
                    responses =
                        listOf(
                            """
                            REMAINING_GAP: The measurement unit is unclear.
                            STATUS: FOLLOW_UP
                            QUESTION: What unit does 20 represent?
                            SUFFICIENCY: 35
                            """.trimIndent()
                        )
                )

            val surveyAi =
                ModelSurveyAi(
                    backend
                )

            val result =
                surveyAi.evaluateResponse(
                    responseEvaluationRequest(
                        originalAnswer =
                            "20"
                    )
                )

            assertEquals(
                ResponseEvaluationResult.FollowUp(
                    sufficiency =
                        35,
                    gap =
                        "The measurement unit is unclear.",
                    question =
                        "What unit does 20 represent?",
                ),
                result,
            )

            assertEquals(
                1,
                backend.generateCallCount,
            )
        }

    @Test
    fun responseEvaluation_passesCompleteFollowUpHistoryToPrompt() =
        runBlocking {
            val backend =
                FakeTextGenerationBackend(
                    responses =
                        listOf(
                            """
                            REMAINING_GAP: NONE
                            STATUS: DONE
                            QUESTION: NONE
                            SUFFICIENCY: 100
                            """.trimIndent()
                        )
                )

            val surveyAi =
                ModelSurveyAi(
                    backend
                )

            surveyAi.evaluateResponse(
                responseEvaluationRequest(
                    originalAnswer =
                        "20",
                    previousFollowUps =
                        listOf(
                            ResponseFollowUpExchange(
                                question =
                                    "What unit does 20 represent?",
                                answer =
                                    "percent",
                            ),
                            ResponseFollowUpExchange(
                                question =
                                    "Is that representative of the last three seasons?",
                                answer =
                                    "Yes",
                            ),
                        ),
                )
            )

            assertEquals(
                1,
                backend.generateCallCount,
            )

            val generationRequest =
                backend.requests.single()

            assertTrue(
                generationRequest.prompt.contains(
                    "What unit does 20 represent?"
                )
            )

            assertTrue(
                generationRequest.prompt.contains(
                    "percent"
                )
            )

            assertTrue(
                generationRequest.prompt.contains(
                    "Is that representative of the last three seasons?"
                )
            )

            assertTrue(
                generationRequest.prompt.contains(
                    "Yes"
                )
            )
        }

    @Test
    fun responseEvaluation_usesInterviewerInstructionAsSystemInstruction() =
        runBlocking {
            val backend =
                FakeTextGenerationBackend(
                    responses =
                        listOf(
                            """
                            REMAINING_GAP: NONE
                            STATUS: DONE
                            QUESTION: NONE
                            SUFFICIENCY: 100
                            """.trimIndent()
                        )
                )

            val surveyAi =
                ModelSurveyAi(
                    backend
                )

            surveyAi.evaluateResponse(
                responseEvaluationRequest()
            )

            val generationRequest =
                backend.requests.single()

            assertEquals(
                "Ask questions neutrally and professionally.",
                generationRequest.systemInstruction,
            )

            assertEquals(
                0.0f,
                generationRequest.temperature,
            )
        }

    @Test
    fun responseEvaluation_malformedOutput_returnsFailureWithoutRetry() =
        runBlocking {
            val backend =
                FakeTextGenerationBackend(
                    responses =
                        listOf(
                            "The answer looks mostly complete."
                        )
                )

            val surveyAi =
                ModelSurveyAi(
                    backend
                )

            val result =
                surveyAi.evaluateResponse(
                    responseEvaluationRequest()
                )

            assertTrue(
                result is ResponseEvaluationResult.Failed
            )

            /*
             * Protocol failure must not trigger another SLM call during the
             * same respondent turn.
             */
            assertEquals(
                1,
                backend.generateCallCount,
            )
        }

    @Test
    fun responseEvaluation_backendFailure_returnsFailure() =
        runBlocking {
            val backend =
                FakeTextGenerationBackend(
                    initializationFailure =
                        IllegalStateException(
                            "Model initialization failed"
                        )
                )

            val surveyAi =
                ModelSurveyAi(
                    backend
                )

            val result =
                surveyAi.evaluateResponse(
                    responseEvaluationRequest()
                )

            assertTrue(
                result is ResponseEvaluationResult.Failed
            )

            val failed =
                result as ResponseEvaluationResult.Failed

            assertTrue(
                failed.reason.contains(
                    "Model initialization failed"
                )
            )
        }

    @Test
    fun backendInitializationFailure_returnsAiFailure() =
        runBlocking {
            val backend =
                FakeTextGenerationBackend(
                    initializationFailure =
                        IllegalStateException(
                            "Model initialization failed"
                        )
                )

            val surveyAi =
                ModelSurveyAi(
                    backend
                )

            val result =
                surveyAi.validateAnswer(
                    semanticRequest()
                )

            assertTrue(
                result is SemanticValidationResult.Failed
            )

            val failed =
                result as SemanticValidationResult.Failed

            assertTrue(
                failed.reason.contains(
                    "Model initialization failed"
                )
            )

            assertFalse(
                backend.isReady()
            )
        }

    @Test
    fun backendIsInitializedOnlyOnceAcrossValidations() =
        runBlocking {
            val backend =
                FakeTextGenerationBackend(
                    responses =
                        listOf(
                            "SUPPORTED: 20",
                            "SUPPORTED: percent",
                            "SUPPORTED: averaged over the last three seasons",
                            "SUPPORTED: 20",
                            "SUPPORTED: percent",
                            "SUPPORTED: averaged over the last three seasons",
                        )
                )

            val surveyAi =
                ModelSurveyAi(
                    backend
                )

            surveyAi.validateAnswer(
                semanticRequest()
            )

            surveyAi.validateAnswer(
                semanticRequest()
            )

            assertEquals(
                1,
                backend.initializeCallCount,
            )

            assertEquals(
                6,
                backend.generateCallCount,
            )
        }

    private fun semanticRequest(
        originalAnswer: String =
            "20 percent, averaged over the last three seasons",
        criteria: List<String> =
            listOf(
                "The respondent explicitly provides a yield-loss magnitude.",
                "The respondent explicitly provides a unit for the yield-loss magnitude.",
                "The respondent explicitly indicates that the value represents " +
                        "the last three seasons or an average across those seasons.",
            ),
        previousClarifications: List<ClarificationExchange> =
            emptyList(),
    ): SemanticValidationRequest {
        return SemanticValidationRequest(
            surveyId =
                "agriculture_maize",
            language =
                "en",
            interviewerInstruction =
                "Ask questions neutrally and professionally.",
            questionId =
                "Q1",
            question =
                "How much yield do you lose because of fall armyworm? " +
                        "Please think back over the last 3 seasons. " +
                        "Percent or bags per acre are fine.",
            originalAnswer =
                originalAnswer,
            validationGoal =
                "Obtain the respondent's average fall armyworm yield loss " +
                        "over the last three seasons using a clear measurable unit.",
            criteria =
                criteria,
            previousClarifications =
                previousClarifications,
        )
    }

    private fun responseEvaluationRequest(
        originalAnswer: String =
            "20 percent, averaged over the last three seasons",
        previousFollowUps: List<ResponseFollowUpExchange> =
            emptyList(),
    ): ResponseEvaluationRequest {
        return ResponseEvaluationRequest(
            surveyId =
                "agriculture_maize",
            language =
                "en",
            interviewerInstruction =
                "Ask questions neutrally and professionally.",
            questionId =
                "Q1",
            question =
                "How much yield do you lose because of fall armyworm? " +
                        "Please think back over the last 3 seasons. " +
                        "Percent or bags per acre are fine.",
            interviewGoal =
                "Understand the respondent's fall armyworm yield loss well " +
                        "enough to know the approximate magnitude, how it is " +
                        "measured, and whether it is representative of recent " +
                        "seasons.",
            originalAnswer =
                originalAnswer,
            previousFollowUps =
                previousFollowUps,
        )
    }

    private fun followUpRequest():
            FollowUpEvaluationRequest {
        return FollowUpEvaluationRequest(
            surveyId =
                "agriculture_maize",
            language =
                "en",
            interviewerInstruction =
                "Ask questions neutrally and professionally.",
            questionId =
                "Q1",
            majorQuestion =
                "How much yield do you lose because of fall armyworm?",
            majorAnswer =
                "20 percent",
            targetId =
                "recent_season_context",
            targetDescription =
                "Understand whether the most recent season differed " +
                        "from the respondent's average.",
            previousFollowUps =
                emptyList(),
        )
    }
}