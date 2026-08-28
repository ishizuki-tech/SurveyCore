package com.negi.surveycore.survey.reference.agriculture

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.surveycore.ai.backend.GenerationRequest
import com.negi.surveycore.ai.backend.GenerationResult
import com.negi.surveycore.ai.backend.TextGenerationBackend
import com.negi.surveycore.ai.backend.litertlm.LiteRtLmBackend
import com.negi.surveycore.ai.model.ModelSurveyAi
import com.negi.surveycore.survey.core.controller.SurveyController
import com.negi.surveycore.survey.core.engine.EngineAction
import com.negi.surveycore.survey.core.engine.SurveyEngine
import com.negi.surveycore.survey.core.engine.SurveyPhase
import com.negi.surveycore.survey.source.AssetSurveySource
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device end-to-end validation for Agriculture Q1.
 *
 * This test exercises the complete production path:
 *
 * production YAML
 *     -> SurveyEngine
 *     -> SurveyController
 *     -> ModelSurveyAi
 *     -> LiteRtLmBackend
 *     -> Gemma
 *     -> response parser
 *     -> SurveyController
 *     -> stored survey session
 *
 * The scenario intentionally requires two respondent turns:
 *
 * 1. "20" must trigger a response follow-up because the unit is ambiguous.
 * 2. "percent" answers that generated follow-up and must complete Q1.
 *
 * The exact follow-up wording and final sufficiency score are model outputs,
 * so the test validates their semantics and storage rather than matching one
 * fixed generated sentence or score.
 */
@RunWith(AndroidJUnit4::class)
class AgricultureQ1EndToEndModelValidationTest {

    @Test
    fun gemma_q1_bareNumber_thenUnitAnswer_advancesToQ2() {
        runBlocking {
            val context =
                InstrumentationRegistry
                    .getInstrumentation()
                    .targetContext

            val definition =
                AssetSurveySource(
                    context =
                        context,
                    assetPath =
                        AGRICULTURE_SURVEY_ASSET,
                ).load()

            val modelFile =
                File(
                    context.filesDir,
                    GEMMA_MODEL_RELATIVE_PATH,
                )

            assertTrue(
                "Gemma model not found: ${modelFile.absolutePath}",
                modelFile.isFile,
            )

            assertTrue(
                "Gemma model is empty: ${modelFile.absolutePath}",
                modelFile.length() > 0L,
            )

            Log.d(
                LOG_TAG,
                "Using model: ${modelFile.absolutePath}",
            )

            Log.d(
                LOG_TAG,
                "Model size: ${modelFile.length()} bytes",
            )

            val runtimeBackend =
                LiteRtLmBackend(
                    modelPath =
                        modelFile.absolutePath,
                    cacheDir =
                        context
                            .cacheDir
                            .absolutePath,
                )

            /*
             * Trace the exact GenerationRequest seen by the real runtime.
             * This is test-only instrumentation and does not change
             * production prompt construction or model behavior.
             */
            val backend =
                PromptTracingBackend(
                    delegate =
                        runtimeBackend,
                )

            val surveyAi =
                ModelSurveyAi(
                    backend =
                        backend,
                    debugLogger = {
                            message ->

                        Log.d(
                            LOG_TAG,
                            message,
                        )
                    },
                )

            try {
                val controller =
                    SurveyController(
                        engine =
                            SurveyEngine(
                                definition =
                                    definition,
                            ),
                        surveyAi =
                            surveyAi,
                    )

                /*
                 * Start the production Agriculture survey and verify that Q1
                 * is the first major question presented to the respondent.
                 */
                val startAction =
                    controller.start()

                assertTrue(
                    "Expected Q1 major question but received $startAction",
                    startAction is EngineAction.AskMajorQuestion,
                )

                val firstQuestion =
                    startAction as EngineAction.AskMajorQuestion

                assertEquals(
                    Q1_ID,
                    firstQuestion.questionId,
                )

                assertEquals(
                    SurveyPhase.AWAITING_MAJOR_ANSWER,
                    controller.state.phase,
                )

                Log.d(
                    LOG_TAG,
                    "[TURN-1] respondent='$INITIAL_ANSWER'",
                )

                /*
                 * "20" is intentionally ambiguous because the production Q1
                 * offers more than one possible measurement unit.
                 */
                val followUpAction =
                    controller.submitText(
                        INITIAL_ANSWER
                    )

                assertTrue(
                    "Expected response follow-up after '$INITIAL_ANSWER' " +
                            "but received $followUpAction",
                    followUpAction is EngineAction.AskResponseFollowUp,
                )

                val followUp =
                    followUpAction as EngineAction.AskResponseFollowUp

                assertEquals(
                    Q1_ID,
                    followUp.questionId,
                )

                assertTrue(
                    "Generated follow-up question must not be blank.",
                    followUp.question.isNotBlank(),
                )

                assertEquals(
                    SurveyPhase.AWAITING_RESPONSE_FOLLOW_UP,
                    controller.state.phase,
                )

                /*
                 * The major answer must not be accepted before the required
                 * follow-up has been evaluated successfully.
                 */
                assertTrue(
                    "Q1 must not be stored before the follow-up is resolved.",
                    controller.session.answers.isEmpty(),
                )

                Log.d(
                    LOG_TAG,
                    "[TURN-1] follow-up='${followUp.question}'",
                )

                Log.d(
                    LOG_TAG,
                    "[TURN-2] respondent='$FOLLOW_UP_ANSWER'",
                )

                /*
                 * The follow-up response must be evaluated together with the
                 * original answer and the generated follow-up question.
                 */
                val nextQuestionAction =
                    controller.submitText(
                        FOLLOW_UP_ANSWER
                    )

                assertTrue(
                    "Expected Q2 after resolving Q1 but received $nextQuestionAction",
                    nextQuestionAction is EngineAction.AskMajorQuestion,
                )

                val nextQuestion =
                    nextQuestionAction as EngineAction.AskMajorQuestion

                assertEquals(
                    Q2_ID,
                    nextQuestion.questionId,
                )

                assertEquals(
                    SurveyPhase.AWAITING_MAJOR_ANSWER,
                    controller.state.phase,
                )

                /*
                 * Verify the complete accepted Q1 evidence stored by the real
                 * controller after the model returns DONE.
                 */
                val storedRecord =
                    controller
                        .session
                        .answers[
                        Q1_ID
                    ]

                assertNotNull(
                    "Q1 record must be stored after the follow-up completes it.",
                    storedRecord,
                )

                checkNotNull(
                    storedRecord
                )

                assertEquals(
                    INITIAL_ANSWER,
                    storedRecord.originalAnswer,
                )

                assertEquals(
                    1,
                    storedRecord.responseFollowUps.size,
                )

                val storedExchange =
                    storedRecord
                        .responseFollowUps
                        .single()

                assertEquals(
                    followUp.question,
                    storedExchange.question,
                )

                assertEquals(
                    FOLLOW_UP_ANSWER,
                    storedExchange.answer,
                )

                val finalSufficiency =
                    storedRecord.finalSufficiency

                assertNotNull(
                    "Q1 must store the final DONE sufficiency.",
                    finalSufficiency,
                )

                checkNotNull(
                    finalSufficiency
                )

                assertTrue(
                    "Final Q1 sufficiency must be in the DONE range, " +
                            "but was $finalSufficiency.",
                    finalSufficiency in
                            MIN_DONE_SUFFICIENCY..MAX_SUFFICIENCY,
                )

                assertTrue(
                    "Legacy clarifications must remain unused for this flow.",
                    storedRecord.clarifications.isEmpty(),
                )

                Log.d(
                    LOG_TAG,
                    buildString {
                        append(
                            "E2E PASS: "
                        )

                        append(
                            "Q1 original='$INITIAL_ANSWER', "
                        )

                        append(
                            "followUp='${followUp.question}', "
                        )

                        append(
                            "followUpAnswer='$FOLLOW_UP_ANSWER', "
                        )

                        append(
                            "finalSufficiency=$finalSufficiency, "
                        )

                        append(
                            "nextQuestion=${nextQuestion.questionId}"
                        )
                    },
                )
            } finally {
                surveyAi.close()
            }
        }
    }

    /**
     * Test-only backend decorator that logs the exact prompt passed to the
     * runtime while preserving the production backend behavior unchanged.
     *
     * Long Android log entries are chunked so the newest-turn section and
     * output contract can be inspected without logcat truncation.
     */
    private class PromptTracingBackend(
        private val delegate: TextGenerationBackend,
    ) : TextGenerationBackend {

        override val backendId: String =
            delegate.backendId

        private var generationNumber =
            0

        override suspend fun initialize() {
            delegate.initialize()
        }

        override suspend fun generate(
            request: GenerationRequest,
        ): GenerationResult {
            generationNumber +=
                1

            Log.d(
                PROMPT_LOG_TAG,
                "===== GENERATION $generationNumber PROMPT BEGIN =====",
            )

            request
                .prompt
                .chunked(
                    LOG_CHUNK_SIZE
                )
                .forEachIndexed {
                        index,
                        chunk,
                    ->

                    Log.d(
                        PROMPT_LOG_TAG,
                        "[$generationNumber:${index + 1}] $chunk",
                    )
                }

            Log.d(
                PROMPT_LOG_TAG,
                "===== GENERATION $generationNumber PROMPT END =====",
            )

            return delegate.generate(
                request
            )
        }

        override fun isReady(): Boolean {
            return delegate.isReady()
        }

        override fun close() {
            delegate.close()
        }
    }

    private companion object {

        const val LOG_TAG =
            "Q1EndToEndValidation"

        const val PROMPT_LOG_TAG =
            "Q1PromptTrace"

        const val LOG_CHUNK_SIZE =
            3000

        const val AGRICULTURE_SURVEY_ASSET =
            "surveys/agriculture_maize_v2.yaml"

        const val GEMMA_MODEL_RELATIVE_PATH =
            "models/gemma-3n-E2B-it-int4.litertlm"

        const val Q1_ID =
            "Q1"

        const val Q2_ID =
            "Q2"

        const val INITIAL_ANSWER =
            "20"

        const val FOLLOW_UP_ANSWER =
            "percent"

        const val MIN_DONE_SUFFICIENCY =
            81

        const val MAX_SUFFICIENCY =
            100
    }
}
