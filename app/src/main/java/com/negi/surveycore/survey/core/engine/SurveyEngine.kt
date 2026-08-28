package com.negi.surveycore.survey.core.engine

import com.negi.surveycore.survey.core.ai.ClarificationExchange
import com.negi.surveycore.survey.core.ai.ResponseEvaluationRequest
import com.negi.surveycore.survey.core.ai.ResponseEvaluationResult
import com.negi.surveycore.survey.core.ai.ResponseFollowUpExchange
import com.negi.surveycore.survey.core.ai.SemanticValidationRequest
import com.negi.surveycore.survey.core.ai.SemanticValidationResult
import com.negi.surveycore.survey.core.model.EndNode
import com.negi.surveycore.survey.core.model.QuestionNode
import com.negi.surveycore.survey.core.model.ReviewNode
import com.negi.surveycore.survey.core.model.StartNode
import com.negi.surveycore.survey.core.model.SurveyDefinition
import com.negi.surveycore.survey.core.model.SurveyNode

/**
 * Deterministic runtime for executing one SurveyDefinition.
 *
 * This class has no Android, UI, LiteRT-LM, llama.cpp, or model-runtime
 * dependency. AI work is exposed as EngineAction requests and evaluated
 * externally.
 *
 * Unified response evaluation is used when a question has an enabled
 * follow-up definition with a nonblank interview goal.
 *
 * The legacy semantic-validation path remains temporarily available so
 * existing survey definitions can continue to run during migration.
 */
class SurveyEngine(
    private val definition: SurveyDefinition,
) {

    var state: SurveyState =
        SurveyState()
        private set

    var session: SurveySession =
        SurveySession()
        private set

    private var pendingOriginalAnswer: String? =
        null

    /*
     * Unified response-evaluation state.
     */
    private var pendingResponseFollowUps:
            MutableList<ResponseFollowUpExchange> =
        mutableListOf()

    private var pendingResponseFollowUpQuestion: String? =
        null

    /*
     * Legacy semantic-validation state.
     */
    private var pendingClarifications:
            MutableList<ClarificationExchange> =
        mutableListOf()

    private var pendingClarificationQuestion: String? =
        null

    /**
     * Starts a new survey execution.
     */
    fun start(
        language: String =
            definition.metadata.defaultLanguage,
    ): EngineAction {
        check(state.phase == SurveyPhase.NOT_STARTED) {
            "Survey has already been started."
        }

        state =
            state.copy(
                activeLanguage = language,
            )

        return enterNode(
            definition.flow.startNodeId
        )
    }

    /**
     * Submits an answer to the current major question.
     */
    fun submitMajorAnswer(
        answer: String,
    ): EngineAction {
        check(
            state.phase ==
                    SurveyPhase.AWAITING_MAJOR_ANSWER
        ) {
            "Survey is not waiting for a major answer."
        }

        val node =
            currentQuestionNode()

        return when (
            val validation =
                AnswerValidator.validate(
                    answer = answer,
                    definition = node.question.answer,
                )
        ) {
            ValidationResult.Valid -> {
                resetPendingAnswer()

                pendingOriginalAnswer =
                    answer

                when {
                    shouldUseResponseEvaluation(node) -> {
                        state =
                            state.copy(
                                phase =
                                    SurveyPhase.EVALUATING_RESPONSE
                            )

                        createResponseEvaluationAction(node)
                    }

                    node.question.answer.semanticValidation.enabled -> {
                        state =
                            state.copy(
                                phase =
                                    SurveyPhase.VALIDATING_MAJOR_ANSWER
                            )

                        createSemanticValidationAction(node)
                    }

                    else -> {
                        acceptCurrentMajorAnswer(
                            node = node,
                            finalSufficiency = null,
                        )
                    }
                }
            }

            is ValidationResult.Invalid -> {
                EngineAction.AnswerRejected(
                    questionId = node.id,
                    message = validation.message,
                )
            }
        }
    }

    /**
     * Applies one unified response-evaluation result.
     *
     * The model's semantic decision is not reinterpreted by Kotlin.
     * Kotlin only controls deterministic state transitions and limits.
     */
    fun applyResponseEvaluation(
        result: ResponseEvaluationResult,
    ): EngineAction {
        check(
            state.phase ==
                    SurveyPhase.EVALUATING_RESPONSE
        ) {
            "Survey is not waiting for response evaluation."
        }

        val node =
            currentQuestionNode()

        return when (result) {
            is ResponseEvaluationResult.Done -> {
                acceptCurrentMajorAnswer(
                    node = node,
                    finalSufficiency = result.sufficiency,
                )
            }

            is ResponseEvaluationResult.FollowUp -> {
                handleResponseFollowUpRequest(
                    node = node,
                    question = result.question,
                )
            }

            is ResponseEvaluationResult.Failed -> {
                EngineAction.AiEvaluationFailed(
                    questionId = node.id,
                    reason = result.reason,
                )
            }
        }
    }

    /**
     * Stores one respondent follow-up answer and requests one new integrated
     * response evaluation using the complete accumulated conversation.
     */
    fun submitResponseFollowUpAnswer(
        answer: String,
    ): EngineAction {
        check(
            state.phase ==
                    SurveyPhase.AWAITING_RESPONSE_FOLLOW_UP
        ) {
            "Survey is not waiting for a response follow-up answer."
        }

        val node =
            currentQuestionNode()

        val followUpQuestion =
            checkNotNull(
                pendingResponseFollowUpQuestion
            ) {
                "No response follow-up question is pending."
            }

        if (answer.trim().isEmpty()) {
            return EngineAction.AnswerRejected(
                questionId = node.id,
                message =
                    "A follow-up answer is required.",
            )
        }

        pendingResponseFollowUps +=
            ResponseFollowUpExchange(
                question = followUpQuestion,
                answer = answer,
            )

        pendingResponseFollowUpQuestion =
            null

        state =
            state.copy(
                phase =
                    SurveyPhase.EVALUATING_RESPONSE
            )

        return createResponseEvaluationAction(node)
    }

    /**
     * Applies a result produced by the legacy semantic-validation path.
     */
    fun applySemanticValidation(
        result: SemanticValidationResult,
    ): EngineAction {
        check(
            state.phase ==
                    SurveyPhase.VALIDATING_MAJOR_ANSWER
        ) {
            "Survey is not waiting for semantic validation."
        }

        val node =
            currentQuestionNode()

        return when (result) {
            SemanticValidationResult.Valid -> {
                acceptCurrentMajorAnswer(
                    node = node,
                    finalSufficiency = null,
                )
            }

            is SemanticValidationResult.Clarify -> {
                handleClarificationRequest(
                    node = node,
                    question = result.question,
                )
            }

            is SemanticValidationResult.Failed -> {
                EngineAction.AiEvaluationFailed(
                    questionId = node.id,
                    reason = result.reason,
                )
            }
        }
    }

    /**
     * Stores one legacy respondent clarification and requests semantic
     * validation again using the complete accumulated evidence.
     */
    fun submitClarificationAnswer(
        answer: String,
    ): EngineAction {
        check(
            state.phase ==
                    SurveyPhase.AWAITING_VALIDATION_CLARIFICATION
        ) {
            "Survey is not waiting for a clarification answer."
        }

        val node =
            currentQuestionNode()

        val clarificationQuestion =
            checkNotNull(
                pendingClarificationQuestion
            ) {
                "No clarification question is pending."
            }

        if (answer.trim().isEmpty()) {
            return EngineAction.AnswerRejected(
                questionId = node.id,
                message =
                    "A clarification answer is required.",
            )
        }

        pendingClarifications +=
            ClarificationExchange(
                question = clarificationQuestion,
                answer = answer,
            )

        pendingClarificationQuestion =
            null

        state =
            state.copy(
                phase =
                    SurveyPhase.VALIDATING_MAJOR_ANSWER
            )

        return createSemanticValidationAction(node)
    }

    /**
     * Continues from a review node.
     *
     * Review behavior remains deterministic and does not require AI.
     */
    fun continueFromReview(): EngineAction {
        check(state.phase != SurveyPhase.COMPLETE) {
            "Survey is already complete."
        }

        val node =
            currentNode()

        check(node is ReviewNode) {
            "Current node is not a review node."
        }

        val nextNodeId =
            checkNotNull(
                node.navigation.defaultNextNodeId
            ) {
                "Review node ${node.id} has no next node."
            }

        return enterNode(nextNodeId)
    }

    /**
     * Returns true when the question is configured for the new integrated
     * interviewer path.
     */
    private fun shouldUseResponseEvaluation(
        node: QuestionNode,
    ): Boolean {
        val followUp =
            node.question.followUp

        return followUp.enabled &&
                followUp.goal.isNotBlank()
    }

    /**
     * Builds the unified AI request from the complete current interview
     * evidence.
     */
    private fun createResponseEvaluationAction(
        node: QuestionNode,
    ): EngineAction {
        val language =
            checkNotNull(
                state.activeLanguage
            )

        val followUp =
            node.question.followUp

        check(
            followUp.enabled
        ) {
            "Response evaluation is disabled for question ${node.id}."
        }

        check(
            followUp.goal.isNotBlank()
        ) {
            "Response evaluation goal is blank for question ${node.id}."
        }

        return EngineAction.RequestResponseEvaluation(
            request =
                ResponseEvaluationRequest(
                    surveyId =
                        definition.metadata.id,
                    language =
                        language,
                    interviewerInstruction =
                        definition.interviewer
                            .instruction
                            .resolve(language),
                    questionId =
                        node.id,
                    question =
                        node.question
                            .prompt
                            .resolve(language),
                    interviewGoal =
                        followUp.goal,
                    originalAnswer =
                        checkNotNull(
                            pendingOriginalAnswer
                        ),
                    previousFollowUps =
                        pendingResponseFollowUps.toList(),
                )
        )
    }

    /**
     * Enforces the deterministic follow-up budget and exposes one model
     * generated question to the respondent.
     */
    private fun handleResponseFollowUpRequest(
        node: QuestionNode,
        question: String,
    ): EngineAction {
        val followUp =
            node.question.followUp

        if (
            pendingResponseFollowUps.size >=
            followUp.maxQuestions
        ) {
            resetPendingAnswer()

            state =
                state.copy(
                    phase =
                        SurveyPhase.AWAITING_MAJOR_ANSWER
                )

            return EngineAction.ResponseEvaluationExhausted(
                questionId = node.id,
                message =
                    "Maximum response follow-up count reached.",
            )
        }

        pendingResponseFollowUpQuestion =
            question

        state =
            state.copy(
                phase =
                    SurveyPhase.AWAITING_RESPONSE_FOLLOW_UP
            )

        return EngineAction.AskResponseFollowUp(
            questionId = node.id,
            question = question,
        )
    }

    /**
     * Builds a request for the legacy semantic-validation path.
     */
    private fun createSemanticValidationAction(
        node: QuestionNode,
    ): EngineAction {
        val language =
            checkNotNull(state.activeLanguage)

        val semantic =
            node.question.answer.semanticValidation

        return EngineAction.RequestSemanticValidation(
            request =
                SemanticValidationRequest(
                    surveyId =
                        definition.metadata.id,
                    language =
                        language,
                    interviewerInstruction =
                        definition.interviewer
                            .instruction
                            .resolve(language),
                    questionId =
                        node.id,
                    question =
                        node.question
                            .prompt
                            .resolve(language),
                    originalAnswer =
                        checkNotNull(
                            pendingOriginalAnswer
                        ),
                    validationGoal =
                        semantic.goal,
                    criteria =
                        semantic.criteria,
                    previousClarifications =
                        pendingClarifications.toList(),
                )
        )
    }

    /**
     * Handles one legacy semantic clarification request.
     */
    private fun handleClarificationRequest(
        node: QuestionNode,
        question: String,
    ): EngineAction {
        val semantic =
            node.question.answer.semanticValidation

        if (
            pendingClarifications.size >=
            semantic.maxClarifications
        ) {
            resetPendingAnswer()

            state =
                state.copy(
                    phase =
                        SurveyPhase.AWAITING_MAJOR_ANSWER
                )

            return EngineAction.SemanticValidationExhausted(
                questionId = node.id,
                message =
                    "Maximum semantic clarification count reached.",
            )
        }

        pendingClarificationQuestion =
            question

        state =
            state.copy(
                phase =
                    SurveyPhase.AWAITING_VALIDATION_CLARIFICATION
            )

        return EngineAction.AskClarification(
            questionId = node.id,
            question = question,
        )
    }

    /**
     * Stores the accepted major answer without rewriting respondent text.
     */
    private fun acceptCurrentMajorAnswer(
        node: QuestionNode,
        finalSufficiency: Int?,
    ): EngineAction {
        val record =
            MajorAnswerRecord(
                questionId = node.id,
                originalAnswer =
                    checkNotNull(
                        pendingOriginalAnswer
                    ),
                clarifications =
                    pendingClarifications.toList(),
                responseFollowUps =
                    pendingResponseFollowUps.toList(),
                finalSufficiency =
                    finalSufficiency,
            )

        session =
            session.copy(
                answers =
                    session.answers +
                            (node.id to record)
            )

        resetPendingAnswer()

        val nextNodeId =
            checkNotNull(
                node.navigation.defaultNextNodeId
            ) {
                "Question node ${node.id} has no next node."
            }

        return enterNode(nextNodeId)
    }

    private fun enterNode(
        nodeId: String,
    ): EngineAction {
        val node =
            findNode(nodeId)

        state =
            state.copy(
                currentNodeId = node.id,
            )

        return when (node) {
            is StartNode -> {
                enterNode(node.nextNodeId)
            }

            is QuestionNode -> {
                state =
                    state.copy(
                        phase =
                            SurveyPhase.AWAITING_MAJOR_ANSWER
                    )

                EngineAction.AskMajorQuestion(
                    questionId = node.id,
                    prompt =
                        node.question
                            .prompt
                            .resolve(
                                checkNotNull(
                                    state.activeLanguage
                                )
                            ),
                )
            }

            is ReviewNode -> {
                EngineAction.ShowReview(
                    nodeId = node.id
                )
            }

            is EndNode -> {
                state =
                    state.copy(
                        phase =
                            SurveyPhase.COMPLETE
                    )

                EngineAction.Complete(
                    completionMessage =
                        node.completionMessage
                            ?.resolve(
                                checkNotNull(
                                    state.activeLanguage
                                )
                            ),
                )
            }
        }
    }

    private fun currentQuestionNode():
            QuestionNode {
        val node =
            currentNode()

        check(node is QuestionNode) {
            "Current node is not a question node."
        }

        return node
    }

    private fun currentNode():
            SurveyNode {
        return findNode(
            checkNotNull(
                state.currentNodeId
            ) {
                "No current survey node."
            }
        )
    }

    private fun findNode(
        nodeId: String,
    ): SurveyNode {
        return definition.flow.nodes
            .firstOrNull {
                it.id == nodeId
            }
            ?: error(
                "Survey node not found: $nodeId"
            )
    }

    /**
     * Clears all pending state for the current major answer.
     */
    private fun resetPendingAnswer() {
        pendingOriginalAnswer =
            null

        pendingResponseFollowUps
            .clear()

        pendingResponseFollowUpQuestion =
            null

        pendingClarifications
            .clear()

        pendingClarificationQuestion =
            null
    }
}
