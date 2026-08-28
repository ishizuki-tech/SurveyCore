package com.negi.surveycore.survey.reference.agriculture

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.surveycore.ai.backend.ProcessingTextGenerationBackend
import com.negi.surveycore.ai.backend.TextGenerationBackend
import com.negi.surveycore.ai.backend.litertlm.LiteRtLmBackend
import com.negi.surveycore.ai.backend.llamacpp.LlamaCppBackend
import com.negi.surveycore.ai.backend.llamacpp.QwenNoThinkPromptProcessor
import com.negi.surveycore.ai.backend.llamacpp.QwenThinkingOutputProcessor
import com.negi.surveycore.ai.model.ModelSurveyAi
import com.negi.surveycore.survey.core.ai.ResponseEvaluationRequest
import com.negi.surveycore.survey.core.ai.ResponseEvaluationResult
import com.negi.surveycore.survey.core.controller.SurveyController
import com.negi.surveycore.survey.core.engine.EngineAction
import com.negi.surveycore.survey.core.engine.SurveyEngine
import com.negi.surveycore.survey.core.model.QuestionNode
import com.negi.surveycore.survey.core.model.SurveyDefinition
import com.negi.surveycore.survey.source.AssetSurveySource
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device comparison harness for Agriculture Q1 across the currently
 * supported local SLM backends.
 *
 * This class intentionally does not fail the JUnit test when a model makes a
 * wrong interviewer decision. Model-quality failures are benchmark results,
 * not infrastructure failures. They are logged with pass=false so E2B, E4B,
 * and Qwen can be compared under the exact same:
 *
 * - production Agriculture YAML,
 * - ModelSurveyAi,
 * - response-evaluation prompt,
 * - response parser,
 * - SurveyController,
 * - SurveyEngine.
 *
 * The test fails only for comparison-infrastructure problems such as a missing
 * model file, an empty model file, or an unexpected exception that prevents a
 * model from being evaluated.
 *
 * Each model runs:
 *
 * Q1-A: "20"         -> expected FOLLOW_UP
 * Q1-B: "20 percent" -> expected DONE
 * Q1-C: "I don't know" -> expected FOLLOW_UP
 * Q1-D: "20" -> generated follow-up -> "percent" -> expected Q2
 *
 * RESULT and SUMMARY log lines use a compact pipe-delimited format so they can
 * be copied into a spreadsheet or parsed later without changing production
 * code.
 */
@RunWith(AndroidJUnit4::class)
class AgricultureQ1ModelComparisonTest {

    @Test
    fun gemmaE2B() {
        runBlocking {
            runModelComparison(
                MODEL_GEMMA_E2B
            )
        }
    }

    @Test
    fun gemmaE4B() {
        runBlocking {
            runModelComparison(
                MODEL_GEMMA_E4B
            )
        }
    }

    @Test
    fun qwen17B() {
        runBlocking {
            runModelComparison(
                MODEL_QWEN_17B
            )
        }
    }

    /**
     * Runs the same single-turn and multi-turn Q1 evaluation set for one
     * model while keeping all survey and orchestration code unchanged.
     */
    private suspend fun runModelComparison(
        model: ModelSpec,
    ) {
        val context =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext

        val definition =
            loadDefinition(
                context
            )

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
                model.relativePath,
            )

        assertTrue(
            "${model.name} model not found: ${modelFile.absolutePath}",
            modelFile.isFile,
        )

        assertTrue(
            "${model.name} model is empty: ${modelFile.absolutePath}",
            modelFile.length() > 0L,
        )

        Log.d(
            LOG_TAG,
            "BEGIN|model=${model.name}|" +
                    "backend=${model.backendLabel}|" +
                    "path=${modelFile.absolutePath}|" +
                    "bytes=${modelFile.length()}",
        )

        val backend =
            createBackend(
                context =
                    context,
                model =
                    model,
                modelFile =
                    modelFile,
            )

        val initializeStart =
            System.nanoTime()

        backend.initialize()

        val initializationMs =
            elapsedMilliseconds(
                initializeStart
            )

        Log.d(
            LOG_TAG,
            "INIT|model=${model.name}|" +
                    "backend=${backend.backendId}|" +
                    "latencyMs=$initializationMs",
        )

        val surveyAi =
            ModelSurveyAi(
                backend =
                    backend,
                debugLogger = {
                        message ->

                    Log.d(
                        DETAIL_LOG_TAG,
                        "[${model.name}] $message",
                    )
                },
            )

        try {
            val singleTurnResults =
                SINGLE_TURN_SCENARIOS.map {
                        scenario ->

                    runSingleTurnScenario(
                        model =
                            model,
                        definition =
                            definition,
                        language =
                            language,
                        q1 =
                            q1,
                        surveyAi =
                            surveyAi,
                        scenario =
                            scenario,
                    )
                }

            val multiTurnResult =
                runMultiTurnScenario(
                    model =
                        model,
                    definition =
                        definition,
                    surveyAi =
                        surveyAi,
                )

            logSummary(
                model =
                    model,
                initializationMs =
                    initializationMs,
                singleTurnResults =
                    singleTurnResults,
                multiTurnResult =
                    multiTurnResult,
            )
        } finally {
            surveyAi.close()
        }
    }

    /**
     * Evaluates one direct respondent answer without follow-up history.
     */
    private suspend fun runSingleTurnScenario(
        model: ModelSpec,
        definition: SurveyDefinition,
        language: String,
        q1: QuestionNode,
        surveyAi: ModelSurveyAi,
        scenario: SingleTurnScenario,
    ): SingleTurnResult {
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
                    scenario.answer,
                previousFollowUps =
                    emptyList(),
            )

        val start =
            System.nanoTime()

        val result =
            surveyAi.evaluateResponse(
                request
            )

        val latencyMs =
            elapsedMilliseconds(
                start
            )

        val actualStatus =
            result.statusLabel()

        val pass =
            actualStatus ==
                    scenario.expectedStatus.name &&
                    result.hasValidScoreForStatus()

        Log.d(
            LOG_TAG,
            buildString {
                append(
                    "RESULT|model=${model.name}"
                )

                append(
                    "|case=${scenario.id}"
                )

                append(
                    "|kind=single"
                )

                append(
                    "|input=${escapeForLog(scenario.answer)}"
                )

                append(
                    "|expected=${scenario.expectedStatus}"
                )

                append(
                    "|actual=$actualStatus"
                )

                append(
                    "|sufficiency=${result.sufficiencyOrNull() ?: "NA"}"
                )

                append(
                    "|latencyMs=$latencyMs"
                )

                append(
                    "|pass=$pass"
                )

                when (
                    result
                ) {
                    is ResponseEvaluationResult.FollowUp -> {
                        append(
                            "|gap=${escapeForLog(result.gap)}"
                        )

                        append(
                            "|question=${escapeForLog(result.question)}"
                        )
                    }

                    is ResponseEvaluationResult.Failed -> {
                        append(
                            "|reason=${escapeForLog(result.reason)}"
                        )
                    }

                    is ResponseEvaluationResult.Done -> {
                        /*
                         * DONE carries no gap or follow-up question.
                         */
                    }
                }
            },
        )

        return SingleTurnResult(
            scenarioId =
                scenario.id,
            expectedStatus =
                scenario.expectedStatus,
            actualStatus =
                actualStatus,
            sufficiency =
                result.sufficiencyOrNull(),
            latencyMs =
                latencyMs,
            pass =
                pass,
        )
    }

    /**
     * Exercises the real SurveyController two-turn flow:
     *
     * respondent: 20
     * model:      generated follow-up
     * respondent: percent
     * expected:   Q2
     */
    private suspend fun runMultiTurnScenario(
        model: ModelSpec,
        definition: SurveyDefinition,
        surveyAi: ModelSurveyAi,
    ): MultiTurnResult {
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

        val startAction =
            controller.start()

        if (
            startAction !is
                    EngineAction.AskMajorQuestion ||
            startAction.questionId !=
            Q1_ID
        ) {
            val detail =
                "Unexpected survey start action: $startAction"

            Log.d(
                LOG_TAG,
                "RESULT|model=${model.name}|" +
                        "case=Q1-D|kind=multi|" +
                        "expected=Q2|actual=INFRASTRUCTURE_ERROR|" +
                        "pass=false|detail=${escapeForLog(detail)}",
            )

            return MultiTurnResult(
                turn1LatencyMs =
                    0L,
                turn2LatencyMs =
                    0L,
                followUpQuestion =
                    null,
                finalAction =
                    startAction.toString(),
                pass =
                    false,
            )
        }

        val turn1Start =
            System.nanoTime()

        val turn1Action =
            controller.submitText(
                MULTI_TURN_INITIAL_ANSWER
            )

        val turn1LatencyMs =
            elapsedMilliseconds(
                turn1Start
            )

        if (
            turn1Action !is
                    EngineAction.AskResponseFollowUp
        ) {
            Log.d(
                LOG_TAG,
                "RESULT|model=${model.name}|" +
                        "case=Q1-D|kind=multi|" +
                        "turn=1|" +
                        "input=${escapeForLog(MULTI_TURN_INITIAL_ANSWER)}|" +
                        "expected=FOLLOW_UP|" +
                        "actual=${escapeForLog(turn1Action.toString())}|" +
                        "latencyMs=$turn1LatencyMs|" +
                        "pass=false",
            )

            return MultiTurnResult(
                turn1LatencyMs =
                    turn1LatencyMs,
                turn2LatencyMs =
                    0L,
                followUpQuestion =
                    null,
                finalAction =
                    turn1Action.toString(),
                pass =
                    false,
            )
        }

        val followUpQuestion =
            turn1Action.question

        Log.d(
            LOG_TAG,
            "RESULT|model=${model.name}|" +
                    "case=Q1-D|kind=multi|" +
                    "turn=1|" +
                    "input=${escapeForLog(MULTI_TURN_INITIAL_ANSWER)}|" +
                    "expected=FOLLOW_UP|" +
                    "actual=FOLLOW_UP|" +
                    "latencyMs=$turn1LatencyMs|" +
                    "pass=true|" +
                    "question=${escapeForLog(followUpQuestion)}",
        )

        val turn2Start =
            System.nanoTime()

        val turn2Action =
            controller.submitText(
                MULTI_TURN_FOLLOW_UP_ANSWER
            )

        val turn2LatencyMs =
            elapsedMilliseconds(
                turn2Start
            )

        val pass =
            turn2Action is
                    EngineAction.AskMajorQuestion &&
                    turn2Action.questionId ==
                    Q2_ID

        Log.d(
            LOG_TAG,
            "RESULT|model=${model.name}|" +
                    "case=Q1-D|kind=multi|" +
                    "turn=2|" +
                    "input=${escapeForLog(MULTI_TURN_FOLLOW_UP_ANSWER)}|" +
                    "expected=Q2|" +
                    "actual=${escapeForLog(turn2Action.toString())}|" +
                    "latencyMs=$turn2LatencyMs|" +
                    "pass=$pass|" +
                    "previousQuestion=${escapeForLog(followUpQuestion)}",
        )

        return MultiTurnResult(
            turn1LatencyMs =
                turn1LatencyMs,
            turn2LatencyMs =
                turn2LatencyMs,
            followUpQuestion =
                followUpQuestion,
            finalAction =
                turn2Action.toString(),
            pass =
                pass,
        )
    }

    /**
     * Produces one concise summary line per model for side-by-side comparison.
     */
    private fun logSummary(
        model: ModelSpec,
        initializationMs: Long,
        singleTurnResults: List<SingleTurnResult>,
        multiTurnResult: MultiTurnResult,
    ) {
        val singlePassed =
            singleTurnResults.count {
                it.pass
            }

        val averageSingleTurnMs =
            singleTurnResults
                .map {
                    it.latencyMs
                }
                .average()
                .toLong()

        val totalEvaluationMs =
            singleTurnResults.sumOf {
                it.latencyMs
            } +
                    multiTurnResult.turn1LatencyMs +
                    multiTurnResult.turn2LatencyMs

        Log.d(
            LOG_TAG,
            "SUMMARY|model=${model.name}|" +
                    "backend=${model.backendLabel}|" +
                    "single=$singlePassed/${singleTurnResults.size}|" +
                    "multi=${if (multiTurnResult.pass) "1/1" else "0/1"}|" +
                    "initMs=$initializationMs|" +
                    "avgSingleMs=$averageSingleTurnMs|" +
                    "multiTurn1Ms=${multiTurnResult.turn1LatencyMs}|" +
                    "multiTurn2Ms=${multiTurnResult.turn2LatencyMs}|" +
                    "evaluationTotalMs=$totalEvaluationMs",
        )

        Log.d(
            LOG_TAG,
            "END|model=${model.name}",
        )
    }

    /**
     * Creates only the model/runtime-specific backend chain.
     *
     * Everything above TextGenerationBackend remains identical across the
     * comparison. This is the boundary SurveyCore is designed to preserve.
     */
    private fun createBackend(
        context: Context,
        model: ModelSpec,
        modelFile: File,
    ): TextGenerationBackend {
        return when (
            model.runtime
        ) {
            RuntimeType.LITERT_LM ->
                LiteRtLmBackend(
                    modelPath =
                        modelFile.absolutePath,
                    cacheDir =
                        context
                            .cacheDir
                            .absolutePath,
                )

            RuntimeType.LLAMA_CPP_QWEN -> {
                val runtimeBackend =
                    LlamaCppBackend(
                        modelPath =
                            modelFile.absolutePath,
                        contextSize =
                            LLAMA_CONTEXT_SIZE,
                    )

                ProcessingTextGenerationBackend(
                    delegate =
                        runtimeBackend,
                    promptProcessor =
                        QwenNoThinkPromptProcessor,
                    outputProcessor =
                        QwenThinkingOutputProcessor,
                )
            }
        }
    }

    private fun loadDefinition(
        context: Context,
    ): SurveyDefinition {
        return AssetSurveySource(
            context =
                context,
            assetPath =
                AGRICULTURE_SURVEY_ASSET,
        ).load()
    }

    private fun ResponseEvaluationResult.statusLabel():
            String {
        return when (
            this
        ) {
            is ResponseEvaluationResult.Done ->
                ExpectedStatus.DONE.name

            is ResponseEvaluationResult.FollowUp ->
                ExpectedStatus.FOLLOW_UP.name

            is ResponseEvaluationResult.Failed ->
                "FAILED"
        }
    }

    private fun ResponseEvaluationResult.sufficiencyOrNull():
            Int? {
        return when (
            this
        ) {
            is ResponseEvaluationResult.Done ->
                sufficiency

            is ResponseEvaluationResult.FollowUp ->
                sufficiency

            is ResponseEvaluationResult.Failed ->
                null
        }
    }

    private fun ResponseEvaluationResult.hasValidScoreForStatus():
            Boolean {
        return when (
            this
        ) {
            is ResponseEvaluationResult.Done ->
                sufficiency in
                        MIN_DONE_SUFFICIENCY..MAX_SUFFICIENCY

            is ResponseEvaluationResult.FollowUp ->
                sufficiency in
                        MIN_SUFFICIENCY..MAX_FOLLOW_UP_SUFFICIENCY

            is ResponseEvaluationResult.Failed ->
                false
        }
    }

    /**
     * Returns elapsed wall-clock time in whole milliseconds.
     */
    private fun elapsedMilliseconds(
        startNanoseconds: Long,
    ): Long {
        return (
                System.nanoTime() -
                        startNanoseconds
                ) /
                1_000_000L
    }

    /**
     * Keeps benchmark log records on one physical logcat line.
     */
    private fun escapeForLog(
        value: String,
    ): String {
        return value
            .replace(
                "\\",
                "\\\\",
            )
            .replace(
                "\r",
                "\\r",
            )
            .replace(
                "\n",
                "\\n",
            )
            .replace(
                "|",
                "\\|",
            )
    }

    private data class ModelSpec(
        val name: String,
        val backendLabel: String,
        val relativePath: String,
        val runtime: RuntimeType,
    )

    private enum class RuntimeType {
        LITERT_LM,
        LLAMA_CPP_QWEN,
    }

    private data class SingleTurnScenario(
        val id: String,
        val answer: String,
        val expectedStatus: ExpectedStatus,
    )

    private data class SingleTurnResult(
        val scenarioId: String,
        val expectedStatus: ExpectedStatus,
        val actualStatus: String,
        val sufficiency: Int?,
        val latencyMs: Long,
        val pass: Boolean,
    )

    private data class MultiTurnResult(
        val turn1LatencyMs: Long,
        val turn2LatencyMs: Long,
        val followUpQuestion: String?,
        val finalAction: String,
        val pass: Boolean,
    )

    private enum class ExpectedStatus {
        DONE,
        FOLLOW_UP,
    }

    private companion object {

        const val LOG_TAG =
            "Q1ModelComparison"

        const val DETAIL_LOG_TAG =
            "Q1ModelCompareDetail"

        const val AGRICULTURE_SURVEY_ASSET =
            "surveys/agriculture_maize_v2.yaml"

        const val Q1_ID =
            "Q1"

        const val Q2_ID =
            "Q2"

        const val MULTI_TURN_INITIAL_ANSWER =
            "20"

        const val MULTI_TURN_FOLLOW_UP_ANSWER =
            "percent"

        const val LLAMA_CONTEXT_SIZE =
            2048

        const val MIN_SUFFICIENCY =
            0

        const val MAX_FOLLOW_UP_SUFFICIENCY =
            80

        const val MIN_DONE_SUFFICIENCY =
            81

        const val MAX_SUFFICIENCY =
            100

        val SINGLE_TURN_SCENARIOS =
            listOf(
                SingleTurnScenario(
                    id =
                        "Q1-A",
                    answer =
                        "20",
                    expectedStatus =
                        ExpectedStatus.FOLLOW_UP,
                ),
                SingleTurnScenario(
                    id =
                        "Q1-B",
                    answer =
                        "20 percent",
                    expectedStatus =
                        ExpectedStatus.DONE,
                ),
                SingleTurnScenario(
                    id =
                        "Q1-C",
                    answer =
                        "I don't know",
                    expectedStatus =
                        ExpectedStatus.FOLLOW_UP,
                ),
            )

        val MODEL_GEMMA_E2B =
            ModelSpec(
                name =
                    "Gemma-3n-E2B-int4",
                backendLabel =
                    "LiteRT-LM",
                relativePath =
                    "models/gemma-3n-E2B-it-int4.litertlm",
                runtime =
                    RuntimeType.LITERT_LM,
            )

        val MODEL_GEMMA_E4B =
            ModelSpec(
                name =
                    "Gemma-3n-E4B-int4",
                backendLabel =
                    "LiteRT-LM",
                relativePath =
                    "models/gemma-3n-E4B-it-int4.litertlm",
                runtime =
                    RuntimeType.LITERT_LM,
            )

        val MODEL_QWEN_17B =
            ModelSpec(
                name =
                    "Qwen3-1.7B-Q4_K_M",
                backendLabel =
                    "llama.cpp",
                relativePath =
                    "models/Qwen3-1.7B-Q4_K_M.gguf",
                runtime =
                    RuntimeType.LLAMA_CPP_QWEN,
            )
    }
}
