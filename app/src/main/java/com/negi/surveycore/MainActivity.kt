package com.negi.surveycore

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.negi.surveycore.ai.backend.litertlm.LiteRtLmBackend
import com.negi.surveycore.ai.model.ModelSurveyAi
import com.negi.surveycore.survey.core.ai.SurveyAi
import com.negi.surveycore.survey.core.controller.SurveyController
import com.negi.surveycore.survey.core.engine.EngineAction
import com.negi.surveycore.survey.core.engine.SurveyEngine
import com.negi.surveycore.survey.core.engine.SurveyPhase
import com.negi.surveycore.survey.core.model.SurveyDefinition
import com.negi.surveycore.survey.source.AssetSurveySource
import com.negi.surveycore.ui.theme.SurveyCoreTheme
import java.io.File
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /*
     * ModelSurveyAi is owned by the Activity because it owns the underlying
     * persistent llama.cpp model and native inference context through the
     * backend chain.
     */
    private lateinit var modelSurveyAi: ModelSurveyAi

    override fun onCreate(
        savedInstanceState: Bundle?,
    ) {
        super.onCreate(savedInstanceState)

        /*
         * Load and validate the survey definition from the YAML asset.
         */
        val surveyDefinition =
            AssetSurveySource(
                context =
                    this,
                assetPath =
                    "surveys/agriculture_maize_v2.yaml",
            ).load()

        /*
         * Resolve the LiteRT-LM model from private application storage.
         */
        val modelFile =
            File(
                filesDir,
                MODEL_RELATIVE_PATH,
            )

        check(
            modelFile.isFile
        ) {
            "LiteRT-LM model not found: ${modelFile.absolutePath}"
        }

        check(
            modelFile.length() > 0L
        ) {
            "LiteRT-LM model is empty: ${modelFile.absolutePath}"
        }

        Log.d(
            MODEL_AI_LOG_TAG,
            "Using model: ${modelFile.absolutePath}",
        )

        Log.d(
            MODEL_AI_LOG_TAG,
            "Model size: ${modelFile.length()} bytes",
        )

        /*
         * LiteRtLmBackend owns only LiteRT-LM runtime mechanics.
         *
         * ModelSurveyAi remains runtime-agnostic and uses the same unified
         * response-evaluation prompt and parser used by the llama.cpp path.
         */
        val backend =
            LiteRtLmBackend(
                modelPath =
                    modelFile.absolutePath,
                cacheDir =
                    cacheDir.absolutePath,
            )

        modelSurveyAi =
            ModelSurveyAi(
                backend =
                    backend,
                debugLogger = {
                        message ->

                    Log.d(
                        MODEL_AI_LOG_TAG,
                        message,
                    )
                },
            )

        setContent {
            SurveyCoreTheme {
                Scaffold(
                    modifier =
                        Modifier.fillMaxSize(),
                ) {
                        innerPadding ->

                    AgricultureSurveyDemo(
                        paddingValues =
                            innerPadding,
                        surveyDefinition =
                            surveyDefinition,
                        surveyAi =
                            modelSurveyAi,
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        if (
            ::modelSurveyAi.isInitialized
        ) {
            modelSurveyAi.close()
        }

        super.onDestroy()
    }

    private companion object {

        const val MODEL_AI_LOG_TAG =
            "ModelSurveyAi"

        const val MODEL_RELATIVE_PATH =
            "models/gemma-3n-E2B-it-int4.litertlm"
    }
}

/**
 * Development UI for exercising a YAML-defined survey with SurveyAi.
 *
 * The UI does not depend on the concrete AI implementation.
 * Survey progression remains inside SurveyEngine and SurveyController.
 */
@Composable
private fun AgricultureSurveyDemo(
    paddingValues: PaddingValues,
    surveyDefinition: SurveyDefinition,
    surveyAi: SurveyAi,
) {
    val controller =
        remember(
            surveyDefinition,
            surveyAi,
        ) {
            SurveyController(
                engine =
                    SurveyEngine(
                        definition =
                            surveyDefinition,
                    ),
                surveyAi =
                    surveyAi,
            )
        }

    val coroutineScope =
        rememberCoroutineScope()

    val transcriptScrollState =
        rememberScrollState()

    val storedResultsScrollState =
        rememberScrollState()

    var input by
    remember {
        mutableStateOf("")
    }

    var transcript by
    remember {
        mutableStateOf(
            emptyList<String>()
        )
    }

    var busy by
    remember {
        mutableStateOf(false)
    }

    LaunchedEffect(
        controller
    ) {
        val action =
            controller.start()

        transcript =
            transcript +
                    actionToTranscript(
                        action
                    )
    }

    LaunchedEffect(
        transcript.size
    ) {
        transcriptScrollState
            .animateScrollTo(
                transcriptScrollState.maxValue
            )
    }

    val phase =
        controller.state.phase

    val canSubmit =
        phase ==
                SurveyPhase.AWAITING_MAJOR_ANSWER ||
                phase ==
                SurveyPhase.AWAITING_RESPONSE_FOLLOW_UP ||
                phase ==
                SurveyPhase.AWAITING_VALIDATION_CLARIFICATION

    val surveyComplete =
        phase ==
                SurveyPhase.COMPLETE

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(
                    paddingValues
                )
                .imePadding()
                .padding(
                    horizontal =
                        20.dp,
                    vertical =
                        16.dp,
                ),
        verticalArrangement =
            Arrangement.spacedBy(
                12.dp
            ),
    ) {
        Text(
            text =
                surveyDefinition
                    .metadata
                    .title
                    .resolve(
                        surveyDefinition
                            .metadata
                            .defaultLanguage
                    ),
            style =
                MaterialTheme
                    .typography
                    .headlineSmall,
        )

        Text(
            text =
                "Survey ID: ${surveyDefinition.metadata.id}",
            style =
                MaterialTheme
                    .typography
                    .labelSmall,
        )

        Text(
            text =
                "AI: Gemma 3n E2B int4 / LiteRT-LM",
            style =
                MaterialTheme
                    .typography
                    .labelSmall,
        )

        Text(
            text =
                "Phase: $phase",
            style =
                MaterialTheme
                    .typography
                    .labelMedium,
        )

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(
                        transcriptScrollState
                    ),
            verticalArrangement =
                Arrangement.spacedBy(
                    10.dp
                ),
        ) {
            for (
            line
            in transcript
            ) {
                Text(
                    text =
                        line,
                    style =
                        MaterialTheme
                            .typography
                            .bodyMedium,
                )
            }
        }

        if (
            !surveyComplete
        ) {
            OutlinedTextField(
                value =
                    input,
                onValueChange = {
                    input =
                        it
                },
                modifier =
                    Modifier.fillMaxWidth(),
                label = {
                    Text(
                        "Your answer"
                    )
                },
                enabled =
                    !busy &&
                            canSubmit,
                minLines =
                    1,
                maxLines =
                    4,
            )

            Button(
                modifier =
                    Modifier.fillMaxWidth(),
                onClick = {
                    val submitted =
                        input.trim()

                    if (
                        submitted.isEmpty()
                    ) {
                        return@Button
                    }

                    transcript =
                        transcript +
                                "You: $submitted"

                    input =
                        ""

                    busy =
                        true

                    coroutineScope.launch {
                        try {
                            val action =
                                controller
                                    .submitText(
                                        submitted
                                    )

                            transcript =
                                transcript +
                                        actionToTranscript(
                                            action
                                        )
                        } catch (
                            exception: Exception
                        ) {
                            transcript =
                                transcript +
                                        "Error: ${
                                            exception.message
                                                ?: exception::class.java.simpleName
                                        }"
                        } finally {
                            busy =
                                false
                        }
                    }
                },
                enabled =
                    !busy &&
                            canSubmit &&
                            input.isNotBlank(),
            ) {
                Text(
                    text =
                        if (
                            busy
                        ) {
                            "Processing..."
                        } else {
                            "Submit"
                        }
                )
            }
        }

        if (
            surveyComplete
        ) {
            Text(
                text =
                    "Stored Results",
                style =
                    MaterialTheme
                        .typography
                        .titleMedium,
            )

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(
                            min =
                                120.dp,
                            max =
                                280.dp,
                        )
                        .verticalScroll(
                            storedResultsScrollState
                        ),
                verticalArrangement =
                    Arrangement.spacedBy(
                        12.dp
                    ),
            ) {
                val sortedAnswers =
                    controller
                        .session
                        .answers
                        .toSortedMap()

                for (
                (questionId, record)
                in sortedAnswers
                ) {
                    Text(
                        text =
                            buildString {
                                appendLine(
                                    questionId
                                )

                                appendLine(
                                    "Original answer: " +
                                            record.originalAnswer
                                )

                                if (
                                    record
                                        .responseFollowUps
                                        .isNotEmpty()
                                ) {
                                    appendLine(
                                        "Response follow-ups:"
                                    )

                                    record
                                        .responseFollowUps
                                        .forEachIndexed {
                                                index,
                                                followUp,
                                            ->

                                            appendLine(
                                                "${index + 1}. " +
                                                        followUp.question
                                            )

                                            appendLine(
                                                "   " +
                                                        followUp.answer
                                            )
                                        }
                                }

                                if (
                                    record.finalSufficiency != null
                                ) {
                                    appendLine(
                                        "Final sufficiency: " +
                                                record.finalSufficiency
                                    )
                                }

                                if (
                                    record
                                        .clarifications
                                        .isNotEmpty()
                                ) {
                                    appendLine(
                                        "Legacy clarifications:"
                                    )

                                    record
                                        .clarifications
                                        .forEachIndexed {
                                                index,
                                                clarification,
                                            ->

                                            appendLine(
                                                "${index + 1}. " +
                                                        clarification.question
                                            )

                                            appendLine(
                                                "   " +
                                                        clarification.answer
                                            )
                                        }
                                }
                            },
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                    )
                }
            }
        }
    }
}

/**
 * Converts externally visible SurveyEngine actions into development UI text.
 *
 * Internal AI-request actions should normally be consumed by
 * SurveyController before reaching this function.
 */
private fun actionToTranscript(
    action: EngineAction,
): String {
    return when (
        action
    ) {
        is EngineAction.AskMajorQuestion ->
            buildString {
                append(
                    "Survey ["
                )

                append(
                    action.questionId
                )

                append(
                    "]: "
                )

                append(
                    action.prompt
                )
            }

        is EngineAction.AskResponseFollowUp ->
            buildString {
                append(
                    "Survey ["
                )

                append(
                    action.questionId
                )

                append(
                    " follow-up]: "
                )

                append(
                    action.question
                )
            }

        is EngineAction.AskClarification ->
            buildString {
                append(
                    "Survey ["
                )

                append(
                    action.questionId
                )

                append(
                    " legacy clarification]: "
                )

                append(
                    action.question
                )
            }

        is EngineAction.AnswerRejected ->
            "Validation: ${action.message}"

        is EngineAction.ResponseEvaluationExhausted ->
            "Interview: ${action.message}"

        is EngineAction.SemanticValidationExhausted ->
            "Validation: ${action.message}"

        is EngineAction.AiEvaluationFailed ->
            "AI error: ${action.reason}"

        is EngineAction.ShowReview ->
            "Survey: Review"

        is EngineAction.Complete ->
            "Survey: ${
                action.completionMessage
                    ?: "Survey complete."
            }"

        is EngineAction.RequestResponseEvaluation ->
            "Internal response evaluation requested."

        is EngineAction.RequestSemanticValidation ->
            "Internal legacy AI evaluation requested."
    }
}
