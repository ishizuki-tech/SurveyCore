package com.negi.surveycore.survey.reference.agriculture

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.surveycore.ai.backend.litertlm.LiteRtLmBackend
import com.negi.surveycore.ai.model.ModelSurveyAi
import com.negi.surveycore.survey.core.ai.ResponseEvaluationRequest
import com.negi.surveycore.survey.core.ai.ResponseEvaluationResult
import com.negi.surveycore.survey.core.model.QuestionNode
import com.negi.surveycore.survey.source.AssetSurveySource
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-level semantic validation for the Agriculture Q1 interviewer.
 *
 * Unlike JVM contract tests that use FakeSurveyAi, this test runs the real
 * on-device Gemma model through LiteRT-LM and ModelSurveyAi.
 *
 * The production Agriculture YAML is loaded from app assets so this validates
 * the complete prompt inputs used by the application:
 *
 * - survey question
 * - interview goal
 * - interviewer instruction
 * - respondent answer
 *
 * This test intentionally validates decision semantics rather than exact
 * generated wording. Follow-up wording and sufficiency values may vary across
 * model/runtime versions while the required DONE/FOLLOW_UP decision should
 * remain stable.
 */
@RunWith(AndroidJUnit4::class)
class AgricultureQ1ModelValidationTest {

    @Test
    fun gemma_q1_expectedScenarios() =
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

            val language =
                definition
                    .metadata
                    .defaultLanguage

            val q1 =
                definition
                    .flow
                    .nodes
                    .firstOrNull {
                        it.id == Q1_ID
                    }

            check(
                q1 is QuestionNode
            ) {
                "Expected $Q1_ID to be a QuestionNode."
            }

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

            val backend =
                LiteRtLmBackend(
                    modelPath =
                        modelFile.absolutePath,
                    cacheDir =
                        context
                            .cacheDir
                            .absolutePath,
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
                val scenarios =
                    listOf(
                        Scenario(
                            id =
                                "Q1-A",
                            respondentAnswer =
                                "20",
                            expectedStatus =
                                ExpectedStatus.FOLLOW_UP,
                            rationale =
                                "A bare number is ambiguous because the survey " +
                                        "offers multiple possible measurement units.",
                        ),
                        Scenario(
                            id =
                                "Q1-B",
                            respondentAnswer =
                                "20 percent",
                            expectedStatus =
                                ExpectedStatus.DONE,
                            rationale =
                                "Magnitude and measurement unit are explicit, " +
                                        "and the survey question supplies the " +
                                        "three-season context.",
                        ),
                        Scenario(
                            id =
                                "Q1-C",
                            respondentAnswer =
                                "I don't know",
                            expectedStatus =
                                ExpectedStatus.FOLLOW_UP,
                            rationale =
                                "No yield-loss estimate is provided.",
                        ),
                    )

                val failures =
                    mutableListOf<String>()

                scenarios.forEach {
                        scenario ->

                    val request =
                        ResponseEvaluationRequest(
                            surveyId =
                                definition
                                    .metadata
                                    .id,
                            language =
                                language,
                            interviewerInstruction =
                                definition
                                    .interviewer
                                    .instruction
                                    .resolve(
                                        language
                                    ),
                            questionId =
                                q1.id,
                            question =
                                q1
                                    .question
                                    .prompt
                                    .resolve(
                                        language
                                    ),
                            interviewGoal =
                                q1
                                    .question
                                    .followUp
                                    .goal,
                            originalAnswer =
                                scenario
                                    .respondentAnswer,
                            previousFollowUps =
                                emptyList(),
                        )

                    Log.d(
                        LOG_TAG,
                        buildString {
                            append(
                                "[${scenario.id}] input='"
                            )

                            append(
                                scenario.respondentAnswer
                            )

                            append(
                                "' expected="
                            )

                            append(
                                scenario.expectedStatus
                            )
                        },
                    )

                    val result =
                        surveyAi.evaluateResponse(
                            request
                        )

                    Log.d(
                        LOG_TAG,
                        "[${scenario.id}] actual=$result",
                    )

                    val failure =
                        validateResult(
                            scenario =
                                scenario,
                            result =
                                result,
                        )

                    if (failure == null) {
                        Log.d(
                            LOG_TAG,
                            "[${scenario.id}] PASS",
                        )
                    } else {
                        Log.e(
                            LOG_TAG,
                            "[${scenario.id}] FAIL: $failure",
                        )

                        failures +=
                            failure
                    }
                }

                Log.d(
                    LOG_TAG,
                    "Validation summary: " +
                            "${scenarios.size - failures.size}/${scenarios.size} passed.",
                )

                assertTrue(
                    buildString {
                        appendLine(
                            "Agriculture Q1 model validation failed."
                        )

                        appendLine(
                            "Passed: ${scenarios.size - failures.size}/${scenarios.size}"
                        )

                        if (failures.isNotEmpty()) {
                            appendLine(
                                "Failures:"
                            )

                            failures.forEach {
                                appendLine(
                                    "- $it"
                                )
                            }
                        }
                    },
                    failures.isEmpty(),
                )
            } finally {
                surveyAi.close()
            }
        }

    private fun validateResult(
        scenario: Scenario,
        result: ResponseEvaluationResult,
    ): String? {
        return when (
            scenario.expectedStatus
        ) {
            ExpectedStatus.DONE -> {
                when (result) {
                    is ResponseEvaluationResult.Done -> {
                        if (
                            result.sufficiency !in
                            MIN_DONE_SUFFICIENCY..MAX_SUFFICIENCY
                        ) {
                            "${scenario.id}: expected DONE score in " +
                                    "$MIN_DONE_SUFFICIENCY..$MAX_SUFFICIENCY, " +
                                    "but received ${result.sufficiency}."
                        } else {
                            null
                        }
                    }

                    is ResponseEvaluationResult.FollowUp ->
                        "${scenario.id}: expected DONE but received FOLLOW_UP " +
                                "(score=${result.sufficiency}, gap='${result.gap}', " +
                                "question='${result.question}'). " +
                                "Rationale: ${scenario.rationale}"

                    is ResponseEvaluationResult.Failed ->
                        "${scenario.id}: expected DONE but protocol evaluation failed: " +
                                result.reason
                }
            }

            ExpectedStatus.FOLLOW_UP -> {
                when (result) {
                    is ResponseEvaluationResult.FollowUp -> {
                        if (
                            result.sufficiency !in
                            MIN_SUFFICIENCY..MAX_FOLLOW_UP_SUFFICIENCY
                        ) {
                            "${scenario.id}: expected FOLLOW_UP score in " +
                                    "$MIN_SUFFICIENCY..$MAX_FOLLOW_UP_SUFFICIENCY, " +
                                    "but received ${result.sufficiency}."
                        } else {
                            null
                        }
                    }

                    is ResponseEvaluationResult.Done ->
                        "${scenario.id}: expected FOLLOW_UP but received DONE " +
                                "(score=${result.sufficiency}). " +
                                "Rationale: ${scenario.rationale}"

                    is ResponseEvaluationResult.Failed ->
                        "${scenario.id}: expected FOLLOW_UP but protocol evaluation failed: " +
                                result.reason
                }
            }
        }
    }

    private data class Scenario(
        val id: String,
        val respondentAnswer: String,
        val expectedStatus: ExpectedStatus,
        val rationale: String,
    )

    private enum class ExpectedStatus {
        DONE,
        FOLLOW_UP,
    }

    private companion object {

        const val LOG_TAG =
            "Q1ModelValidation"

        const val AGRICULTURE_SURVEY_ASSET =
            "surveys/agriculture_maize_v2.yaml"

        const val Q1_ID =
            "Q1"

        const val GEMMA_MODEL_RELATIVE_PATH =
            "models/gemma-3n-E2B-it-int4.litertlm"

        const val MIN_SUFFICIENCY =
            0

        const val MAX_FOLLOW_UP_SUFFICIENCY =
            80

        const val MIN_DONE_SUFFICIENCY =
            81

        const val MAX_SUFFICIENCY =
            100
    }
}
