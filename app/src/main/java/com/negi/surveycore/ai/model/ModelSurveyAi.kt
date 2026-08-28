package com.negi.surveycore.ai.model

import com.negi.surveycore.ai.backend.GenerationRequest
import com.negi.surveycore.ai.backend.TextGenerationBackend
import com.negi.surveycore.ai.prompt.CriterionEvaluationPromptBuilder
import com.negi.surveycore.ai.prompt.FollowUpPromptBuilder
import com.negi.surveycore.ai.prompt.ResponseEvaluationPromptBuilder
import com.negi.surveycore.ai.prompt.SemanticClarificationPromptBuilder
import com.negi.surveycore.ai.protocol.CriterionEvaluationParser
import com.negi.surveycore.ai.protocol.CriterionEvaluationResult
import com.negi.surveycore.ai.protocol.FollowUpParser
import com.negi.surveycore.ai.protocol.ResponseEvaluationParser
import com.negi.surveycore.ai.protocol.SemanticClarificationParser
import com.negi.surveycore.survey.core.ai.FollowUpEvaluationRequest
import com.negi.surveycore.survey.core.ai.FollowUpEvaluationResult
import com.negi.surveycore.survey.core.ai.ResponseEvaluationRequest
import com.negi.surveycore.survey.core.ai.ResponseEvaluationResult
import com.negi.surveycore.survey.core.ai.SemanticValidationRequest
import com.negi.surveycore.survey.core.ai.SemanticValidationResult
import com.negi.surveycore.survey.core.ai.SurveyAi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SurveyAi implementation backed by a generic text-generation backend.
 *
 * The class currently supports both the experimental criterion-based
 * validation path and the new unified response-evaluation path.
 *
 * The unified path evaluates one respondent turn with exactly one model
 * generation. It estimates response sufficiency and either finishes the
 * current interview goal or proposes one useful follow-up question.
 *
 * Survey progression, branching, limits, storage, and completion remain
 * deterministic responsibilities of Survey Core.
 *
 * The underlying TextGenerationBackend remains independent from survey
 * semantics and may be backed by LiteRT-LM, llama.cpp, or another runtime.
 */
class ModelSurveyAi(
    private val backend: TextGenerationBackend,
    private val debugLogger: ((String) -> Unit)? = null,
) : SurveyAi, AutoCloseable {

    private val initializationMutex =
        Mutex()

    /**
     * Evaluates one respondent turn as one integrated interviewer decision.
     *
     * Exactly one model generation is performed for this request.
     */
    override suspend fun evaluateResponse(
        request: ResponseEvaluationRequest,
    ): ResponseEvaluationResult {
        return try {
            ensureBackendReady()

            debug(
                "Response evaluation request " +
                        "[questionId=${request.questionId}, " +
                        "previousFollowUps=${request.previousFollowUps.size}]"
            )

            val prompt =
                ResponseEvaluationPromptBuilder.build(
                    request
                )

            val generationResult =
                backend.generate(
                    GenerationRequest(
                        systemInstruction =
                            request.interviewerInstruction,
                        prompt =
                            prompt,
                        maxOutputTokens =
                            RESPONSE_EVALUATION_MAX_OUTPUT_TOKENS,
                        temperature =
                            DETERMINISTIC_TEMPERATURE,
                    )
                )

            debug(
                "Response evaluation raw output " +
                        "[questionId=${request.questionId}]: " +
                        "'${singleLineForLog(generationResult.text)}'"
            )

            val result =
                ResponseEvaluationParser.parse(
                    generationResult.text
                )

            debug(
                "Response evaluation parsed result " +
                        "[questionId=${request.questionId}]: $result"
            )

            result
        } catch (
            exception: CancellationException
        ) {
            throw exception
        } catch (
            exception: Exception
        ) {
            val reason =
                backendFailureReason(
                    operation =
                        "response evaluation",
                    exception =
                        exception,
                )

            debug(
                "Response evaluation failure " +
                        "[questionId=${request.questionId}]: $reason"
            )

            ResponseEvaluationResult.Failed(
                reason =
                    reason
            )
        }
    }

    /**
     * Validates a major answer using the existing experimental
     * criterion-by-criterion path.
     *
     * This path remains temporarily available while Survey Core migrates to
     * unified response evaluation.
     */
    override suspend fun validateAnswer(
        request: SemanticValidationRequest,
    ): SemanticValidationResult {
        return try {
            ensureBackendReady()

            debugSemanticEvidence(
                request
            )

            val criteria =
                effectiveCriteria(
                    request
                )

            if (
                criteria.isEmpty()
            ) {
                return SemanticValidationResult.Failed(
                    reason =
                        "Semantic validation has no criterion or validation goal."
                )
            }

            for (
            (index, criterion)
            in criteria.withIndex()
            ) {
                val criterionNumber =
                    index + 1

                debug(
                    "Criterion evaluation request " +
                            "[questionId=${request.questionId}, " +
                            "criterion=$criterionNumber/${criteria.size}]"
                )

                val generationResult =
                    backend.generate(
                        GenerationRequest(
                            systemInstruction =
                                CriterionEvaluationPromptBuilder
                                    .SYSTEM_INSTRUCTION,
                            prompt =
                                CriterionEvaluationPromptBuilder
                                    .build(
                                        request =
                                            request,
                                        criterion =
                                            criterion,
                                    ),
                            maxOutputTokens =
                                CRITERION_MAX_OUTPUT_TOKENS,
                            temperature =
                                DETERMINISTIC_TEMPERATURE,
                        )
                    )

                debug(
                    "Criterion raw output " +
                            "[questionId=${request.questionId}, " +
                            "criterion=$criterionNumber/${criteria.size}]: " +
                            "'${singleLineForLog(generationResult.text)}'"
                )

                when (
                    val criterionResult =
                        CriterionEvaluationParser
                            .parse(
                                generationResult.text
                            )
                ) {
                    is CriterionEvaluationResult.Supported -> {
                        val evidenceQuote =
                            criterionResult.evidenceQuote

                        if (
                            isGroundedEvidenceQuote(
                                request =
                                    request,
                                evidenceQuote =
                                    evidenceQuote,
                            )
                        ) {
                            debug(
                                "Criterion supported with grounded evidence " +
                                        "[questionId=${request.questionId}, " +
                                        "criterion=$criterionNumber/${criteria.size}, " +
                                        "evidence='${singleLineForLog(evidenceQuote)}']"
                            )

                            continue
                        }

                        debug(
                            "Criterion support was not grounded in respondent evidence " +
                                    "[questionId=${request.questionId}, " +
                                    "criterion=$criterionNumber/${criteria.size}, " +
                                    "evidence='${singleLineForLog(evidenceQuote)}']"
                        )

                        return generateClarification(
                            request =
                                request,
                            missingCriterion =
                                criterion,
                            criterionNumber =
                                criterionNumber,
                            criterionCount =
                                criteria.size,
                        )
                    }

                    CriterionEvaluationResult.Missing -> {
                        debug(
                            "Criterion missing " +
                                    "[questionId=${request.questionId}, " +
                                    "criterion=$criterionNumber/${criteria.size}]"
                        )

                        return generateClarification(
                            request =
                                request,
                            missingCriterion =
                                criterion,
                            criterionNumber =
                                criterionNumber,
                            criterionCount =
                                criteria.size,
                        )
                    }

                    is CriterionEvaluationResult.Failed -> {
                        debug(
                            "Criterion evaluation parse failure " +
                                    "[questionId=${request.questionId}, " +
                                    "criterion=$criterionNumber/${criteria.size}]: " +
                                    criterionResult.reason
                        )

                        return SemanticValidationResult.Failed(
                            reason =
                                criterionResult.reason
                        )
                    }
                }
            }

            debug(
                "Semantic validation complete " +
                        "[questionId=${request.questionId}]: all criteria supported"
            )

            SemanticValidationResult.Valid
        } catch (
            exception: CancellationException
        ) {
            throw exception
        } catch (
            exception: Exception
        ) {
            val reason =
                backendFailureReason(
                    operation =
                        "semantic validation",
                    exception =
                        exception,
                )

            debug(
                "Semantic validation failure " +
                        "[questionId=${request.questionId}]: " +
                        reason
            )

            SemanticValidationResult.Failed(
                reason =
                    reason
            )
        }
    }

    /**
     * Generates one clarification question for the first unsupported
     * criterion in the legacy validation path.
     */
    private suspend fun generateClarification(
        request: SemanticValidationRequest,
        missingCriterion: String,
        criterionNumber: Int,
        criterionCount: Int,
    ): SemanticValidationResult {
        debug(
            "Semantic clarification request " +
                    "[questionId=${request.questionId}, " +
                    "criterion=$criterionNumber/$criterionCount]"
        )

        val generationResult =
            backend.generate(
                GenerationRequest(
                    systemInstruction =
                        SemanticClarificationPromptBuilder
                            .SYSTEM_INSTRUCTION,
                    prompt =
                        SemanticClarificationPromptBuilder
                            .build(
                                request =
                                    request,
                                missingCriterion =
                                    missingCriterion,
                            ),
                    maxOutputTokens =
                        CLARIFICATION_MAX_OUTPUT_TOKENS,
                    temperature =
                        DETERMINISTIC_TEMPERATURE,
                )
            )

        debug(
            "Semantic clarification raw output " +
                    "[questionId=${request.questionId}, " +
                    "criterion=$criterionNumber/$criterionCount]: " +
                    "'${singleLineForLog(generationResult.text)}'"
        )

        return SemanticClarificationParser
            .parse(
                generationResult.text
            )
    }

    /**
     * Returns configured criteria in deterministic evaluation order.
     */
    private fun effectiveCriteria(
        request: SemanticValidationRequest,
    ): List<String> {
        val explicitCriteria =
            request.criteria
                .map {
                    it.trim()
                }
                .filter {
                    it.isNotEmpty()
                }

        if (
            explicitCriteria.isNotEmpty()
        ) {
            return explicitCriteria
        }

        val validationGoal =
            request.validationGoal.trim()

        if (
            validationGoal.isNotEmpty()
        ) {
            return listOf(
                validationGoal
            )
        }

        return emptyList()
    }

    /**
     * Verifies that legacy evidence quoted by the model appears in respondent
     * answers.
     *
     * This is a grounding check only. It does not determine whether the quote
     * semantically proves the criterion.
     */
    private fun isGroundedEvidenceQuote(
        request: SemanticValidationRequest,
        evidenceQuote: String,
    ): Boolean {
        val normalizedQuote =
            normalizeWhitespace(
                evidenceQuote
            )

        if (
            normalizedQuote.isEmpty()
        ) {
            return false
        }

        val respondentEvidence =
            buildList {
                add(
                    request.originalAnswer
                )

                request.previousClarifications
                    .forEach {
                            exchange ->

                        add(
                            exchange.answer
                        )
                    }
            }

        return respondentEvidence.any {
                evidence ->

            containsWholeEvidenceQuote(
                evidence =
                    normalizeWhitespace(
                        evidence
                    ),
                quote =
                    normalizedQuote,
            )
        }
    }

    /**
     * Matches a grounded quote without accepting it as a substring of a
     * larger alphanumeric token.
     */
    private fun containsWholeEvidenceQuote(
        evidence: String,
        quote: String,
    ): Boolean {
        if (
            evidence.isEmpty() ||
            quote.isEmpty()
        ) {
            return false
        }

        val pattern =
            Regex(
                pattern =
                    "(?<![\\p{L}\\p{N}_])" +
                            Regex.escape(
                                quote
                            ) +
                            "(?![\\p{L}\\p{N}_])",
                option =
                    RegexOption.IGNORE_CASE,
            )

        return pattern.containsMatchIn(
            evidence
        )
    }

    /**
     * Normalizes whitespace for legacy evidence grounding.
     */
    private fun normalizeWhitespace(
        text: String,
    ): String {
        return text
            .trim()
            .replace(
                Regex(
                    "\\s+"
                ),
                " ",
            )
    }

    /**
     * Evaluates one legacy research follow-up target.
     */
    override suspend fun evaluateFollowUp(
        request: FollowUpEvaluationRequest,
    ): FollowUpEvaluationResult {
        return try {
            ensureBackendReady()

            val prompt =
                FollowUpPromptBuilder
                    .build(
                        request
                    )

            debug(
                "Follow-up evaluation request " +
                        "[questionId=${request.questionId}, " +
                        "targetId=${request.targetId}, " +
                        "previous=${request.previousFollowUps.size}]"
            )

            val generationResult =
                backend.generate(
                    GenerationRequest(
                        systemInstruction =
                            FollowUpPromptBuilder
                                .SYSTEM_INSTRUCTION,
                        prompt =
                            prompt,
                        maxOutputTokens =
                            FOLLOW_UP_MAX_OUTPUT_TOKENS,
                        temperature =
                            DETERMINISTIC_TEMPERATURE,
                    )
                )

            debug(
                "Follow-up raw output " +
                        "[questionId=${request.questionId}, " +
                        "targetId=${request.targetId}]: " +
                        "'${singleLineForLog(generationResult.text)}'"
            )

            FollowUpParser
                .parse(
                    generationResult.text
                )
        } catch (
            exception: CancellationException
        ) {
            throw exception
        } catch (
            exception: Exception
        ) {
            val reason =
                backendFailureReason(
                    operation =
                        "follow-up evaluation",
                    exception =
                        exception,
                )

            debug(
                "Follow-up evaluation failure " +
                        "[questionId=${request.questionId}, " +
                        "targetId=${request.targetId}]: $reason"
            )

            FollowUpEvaluationResult.Failed(
                reason =
                    reason
            )
        }
    }

    /**
     * Initializes the backend exactly when it is first required.
     */
    private suspend fun ensureBackendReady() {
        if (
            backend.isReady()
        ) {
            return
        }

        initializationMutex.withLock {
            if (
                !backend.isReady()
            ) {
                debug(
                    "Initializing backend '${backend.backendId}'..."
                )

                backend.initialize()

                debug(
                    "Backend '${backend.backendId}' initialization returned."
                )
            }

            check(
                backend.isReady()
            ) {
                "Backend '${backend.backendId}' did not become ready " +
                        "after initialization."
            }

            debug(
                "Backend '${backend.backendId}' is ready."
            )
        }
    }

    /**
     * Writes respondent evidence supplied to the legacy semantic-validation
     * path.
     */
    private fun debugSemanticEvidence(
        request: SemanticValidationRequest,
    ) {
        val message =
            buildString {
                append(
                    "Semantic evidence " +
                            "[questionId=${request.questionId}]: "
                )

                append(
                    "original='" +
                            singleLineForLog(
                                request.originalAnswer
                            ) +
                            "'"
                )

                request.previousClarifications
                    .forEachIndexed {
                            index,
                            exchange,
                        ->

                        append(
                            ", clarification${index + 1}Answer='" +
                                    singleLineForLog(
                                        exchange.answer
                                    ) +
                                    "'"
                        )
                    }
            }

        debug(
            message
        )
    }

    /**
     * Releases resources owned by the text-generation backend chain.
     */
    override fun close() {
        debug(
            "Closing backend '${backend.backendId}'."
        )

        backend.close()
    }

    /**
     * Sends diagnostic output to the optional host-provided logger.
     */
    private fun debug(
        message: String,
    ) {
        try {
            debugLogger?.invoke(
                message
            )
        } catch (
            ignored: Exception
        ) {
            /*
             * Diagnostic logging must never alter survey behavior.
             */
        }
    }

    /**
     * Converts multiline text into a compact representation for logcat.
     */
    private fun singleLineForLog(
        text: String,
    ): String {
        return text
            .replace(
                "\r",
                "\\r"
            )
            .replace(
                "\n",
                "\\n"
            )
    }

    /**
     * Creates a stable backend failure message.
     */
    private fun backendFailureReason(
        operation: String,
        exception: Exception,
    ): String {
        val detail =
            exception.message
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: exception::class.java.simpleName

        return "Backend '${backend.backendId}' failed during " +
                "$operation: $detail"
    }

    private companion object {

        const val CRITERION_MAX_OUTPUT_TOKENS =
            32

        const val CLARIFICATION_MAX_OUTPUT_TOKENS =
            64

        const val RESPONSE_EVALUATION_MAX_OUTPUT_TOKENS =
            128

        const val FOLLOW_UP_MAX_OUTPUT_TOKENS =
            128

        const val DETERMINISTIC_TEMPERATURE =
            0.0f
    }
}
