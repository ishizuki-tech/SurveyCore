package com.negi.surveycore.survey.yaml

import com.negi.surveycore.survey.core.model.AnswerDefinition
import com.negi.surveycore.survey.core.model.AnswerType
import com.negi.surveycore.survey.core.model.EndNode
import com.negi.surveycore.survey.core.model.FollowUpDefinition
import com.negi.surveycore.survey.core.model.NavigationDefinition
import com.negi.surveycore.survey.core.model.QuestionNode
import com.negi.surveycore.survey.core.model.ReviewNode
import com.negi.surveycore.survey.core.model.StartNode
import com.negi.surveycore.survey.core.model.SurveyDefinition
import com.negi.surveycore.survey.core.model.SurveyNode

/**
 * Performs structural and semantic validation of a SurveyDefinition.
 *
 * YAML parsing and survey validation are intentionally separate:
 *
 * SurveyYamlLoader:
 *     YAML -> SurveyDefinition
 *
 * SurveyValidator:
 *     SurveyDefinition -> validation result
 *
 * This validator does not execute survey logic and has no dependency on
 * Android, SurveyAi, LiteRT-LM, llama.cpp, or any model runtime.
 */
object SurveyValidator {

    /**
     * Validates a survey without throwing.
     */
    fun validate(
        definition: SurveyDefinition,
    ): SurveyValidationResult {
        val errors =
            mutableListOf<String>()

        validateMetadata(
            definition = definition,
            errors = errors,
        )

        validateFlow(
            definition = definition,
            errors = errors,
        )

        return if (errors.isEmpty()) {
            SurveyValidationResult.Valid
        } else {
            SurveyValidationResult.Invalid(
                errors = errors.toList(),
            )
        }
    }

    /**
     * Validates a survey and throws when any error is found.
     *
     * This is convenient at application boundaries where an invalid
     * survey should never be passed into SurveyEngine.
     */
    fun requireValid(
        definition: SurveyDefinition,
    ): SurveyDefinition {
        return when (
            val result =
                validate(definition)
        ) {
            SurveyValidationResult.Valid ->
                definition

            is SurveyValidationResult.Invalid ->
                throw SurveyDefinitionException(
                    errors = result.errors
                )
        }
    }

    private fun validateMetadata(
        definition: SurveyDefinition,
        errors: MutableList<String>,
    ) {
        if (definition.schemaVersion != 2) {
            errors +=
                "Unsupported schemaVersion ${definition.schemaVersion}. Expected 2."
        }

        val metadata =
            definition.metadata

        if (metadata.id.isBlank()) {
            errors +=
                "survey.id must not be blank."
        }

        if (metadata.version <= 0) {
            errors +=
                "survey.version must be greater than 0."
        }

        if (metadata.title.default.isBlank()) {
            errors +=
                "survey.title must not be blank."
        }

        if (metadata.defaultLanguage.isBlank()) {
            errors +=
                "survey.defaultLanguage must not be blank."
        }

        if (metadata.supportedLanguages.isEmpty()) {
            errors +=
                "survey.supportedLanguages must not be empty."
        }

        if (
            metadata.defaultLanguage !in
            metadata.supportedLanguages
        ) {
            errors +=
                "survey.defaultLanguage '${metadata.defaultLanguage}' " +
                        "must be included in supportedLanguages."
        }

        val duplicateLanguages =
            findDuplicates(
                metadata.supportedLanguages
            )

        if (duplicateLanguages.isNotEmpty()) {
            errors +=
                "survey.supportedLanguages contains duplicates: " +
                        duplicateLanguages.joinToString()
        }

        if (
            definition.interviewer
                .instruction
                .default
                .isBlank()
        ) {
            errors +=
                "interviewer.instruction must not be blank."
        }
    }

    private fun validateFlow(
        definition: SurveyDefinition,
        errors: MutableList<String>,
    ) {
        val flow =
            definition.flow

        if (flow.nodes.isEmpty()) {
            errors +=
                "flow.nodes must not be empty."

            return
        }

        val nodeIds =
            flow.nodes.map {
                it.id
            }

        val duplicateNodeIds =
            findDuplicates(nodeIds)

        if (duplicateNodeIds.isNotEmpty()) {
            errors +=
                "Duplicate node IDs: " +
                        duplicateNodeIds.joinToString()
        }

        flow.nodes.forEach { node ->
            if (node.id.isBlank()) {
                errors +=
                    "Every survey node must have a non-blank ID."
            }
        }

        /*
         * Build this map only after duplicate IDs have been reported.
         * associateBy would otherwise silently hide one duplicate.
         */
        val nodesById =
            flow.nodes
                .groupBy {
                    it.id
                }
                .mapValues {
                        (_, nodes) ->
                    nodes.first()
                }

        val startNode =
            nodesById[
                flow.startNodeId
            ]

        if (startNode == null) {
            errors +=
                "flow.startNodeId '${flow.startNodeId}' does not exist."
        } else if (
            startNode !is StartNode
        ) {
            errors +=
                "flow.startNodeId '${flow.startNodeId}' must reference a start node."
        }

        val startNodes =
            flow.nodes.filterIsInstance<StartNode>()

        if (startNodes.size != 1) {
            errors +=
                "Survey must contain exactly one start node; found ${startNodes.size}."
        }

        val endNodes =
            flow.nodes.filterIsInstance<EndNode>()

        if (endNodes.isEmpty()) {
            errors +=
                "Survey must contain at least one end node."
        }

        flow.nodes.forEach { node ->
            validateNode(
                node = node,
                nodesById = nodesById,
                errors = errors,
            )
        }

        /*
         * Reachability is checked only when the configured start node
         * exists. Unreachable nodes usually indicate a typo in `next`
         * or dead survey content.
         */
        if (startNode != null) {
            validateReachability(
                startNodeId =
                    flow.startNodeId,
                nodesById =
                    nodesById,
                errors =
                    errors,
            )
        }
    }

    private fun validateNode(
        node: SurveyNode,
        nodesById: Map<String, SurveyNode>,
        errors: MutableList<String>,
    ) {
        when (node) {
            is StartNode -> {
                validateReference(
                    sourceNodeId =
                        node.id,
                    referenceName =
                        "next",
                    targetNodeId =
                        node.nextNodeId,
                    nodesById =
                        nodesById,
                    errors =
                        errors,
                )
            }

            is QuestionNode -> {
                validateQuestionNode(
                    node = node,
                    nodesById = nodesById,
                    errors = errors,
                )
            }

            is ReviewNode -> {
                validateNavigation(
                    sourceNodeId =
                        node.id,
                    navigation =
                        node.navigation,
                    nodesById =
                        nodesById,
                    errors =
                        errors,
                )
            }

            is EndNode -> {
                /*
                 * EndNode intentionally has no outgoing navigation.
                 */
            }
        }
    }

    private fun validateQuestionNode(
        node: QuestionNode,
        nodesById: Map<String, SurveyNode>,
        errors: MutableList<String>,
    ) {
        if (
            node.question
                .prompt
                .default
                .isBlank()
        ) {
            errors +=
                "${node.id}.prompt must not be blank."
        }

        validateAnswerDefinition(
            questionId =
                node.id,
            definition =
                node.question.answer,
            errors =
                errors,
        )

        validateFollowUpDefinition(
            questionId =
                node.id,
            definition =
                node.question.followUp,
            errors =
                errors,
        )

        validateNavigation(
            sourceNodeId =
                node.id,
            navigation =
                node.navigation,
            nodesById =
                nodesById,
            errors =
                errors,
        )
    }

    private fun validateAnswerDefinition(
        questionId: String,
        definition: AnswerDefinition,
        errors: MutableList<String>,
    ) {
        if (definition.inputModes.isEmpty()) {
            errors +=
                "$questionId.answer.inputModes must not be empty."
        }

        when (definition.type) {
            AnswerType.SINGLE_CHOICE,
            AnswerType.MULTI_CHOICE,
                -> {
                if (definition.options.isEmpty()) {
                    errors +=
                        "$questionId.answer.options must not be empty " +
                                "for ${definition.type}."
                }

                val optionIds =
                    definition.options.map {
                        it.id
                    }

                val duplicateOptionIds =
                    findDuplicates(optionIds)

                if (duplicateOptionIds.isNotEmpty()) {
                    errors +=
                        "$questionId.answer.options contains duplicate IDs: " +
                                duplicateOptionIds.joinToString()
                }

                definition.options.forEach { option ->
                    if (option.id.isBlank()) {
                        errors +=
                            "$questionId.answer option ID must not be blank."
                    }

                    if (option.label.default.isBlank()) {
                        errors +=
                            "$questionId.answer option '${option.id}' label must not be blank."
                    }
                }
            }

            else -> {
                /*
                 * Non-choice answers do not currently use options.
                 */
                if (definition.options.isNotEmpty()) {
                    errors +=
                        "$questionId.answer.options is only valid for " +
                                "SINGLE_CHOICE or MULTI_CHOICE answers."
                }
            }
        }

        val deterministic =
            definition.deterministicValidation

        if (
            deterministic.minLength != null &&
            deterministic.minLength < 0
        ) {
            errors +=
                "$questionId.answer.deterministicValidation.minLength " +
                        "must be >= 0."
        }

        if (
            deterministic.maxLength != null &&
            deterministic.maxLength < 0
        ) {
            errors +=
                "$questionId.answer.deterministicValidation.maxLength " +
                        "must be >= 0."
        }

        if (
            deterministic.minLength != null &&
            deterministic.maxLength != null &&
            deterministic.minLength >
            deterministic.maxLength
        ) {
            errors +=
                "$questionId.answer.deterministicValidation.minLength " +
                        "must not exceed maxLength."
        }

        if (
            deterministic.minNumber != null &&
            deterministic.maxNumber != null &&
            deterministic.minNumber >
            deterministic.maxNumber
        ) {
            errors +=
                "$questionId.answer.deterministicValidation.minNumber " +
                        "must not exceed maxNumber."
        }

        val semantic =
            definition.semanticValidation

        if (semantic.maxClarifications < 0) {
            errors +=
                "$questionId.answer.semanticValidation.maxClarifications " +
                        "must be >= 0."
        }

        if (semantic.enabled) {
            if (semantic.goal.isBlank()) {
                errors +=
                    "$questionId.answer.semanticValidation.goal " +
                            "must not be blank when semantic validation is enabled."
            }

            if (semantic.criteria.isEmpty()) {
                errors +=
                    "$questionId.answer.semanticValidation.criteria " +
                            "must not be empty when semantic validation is enabled."
            }

            semantic.criteria.forEachIndexed {
                    index,
                    criterion,
                ->

                if (criterion.isBlank()) {
                    errors +=
                        "$questionId.answer.semanticValidation.criteria[$index] " +
                                "must not be blank."
                }
            }
        }
    }

    private fun validateFollowUpDefinition(
        questionId: String,
        definition: FollowUpDefinition,
        errors: MutableList<String>,
    ) {
        if (definition.maxQuestions < 0) {
            errors +=
                "$questionId.followUp.maxQuestions must be >= 0."
        }

        if (!definition.enabled) {
            return
        }

        if (definition.maxQuestions <= 0) {
            errors +=
                "$questionId.followUp.maxQuestions must be greater than 0 " +
                        "when follow-up is enabled."
        }

        val hasGoal =
            definition.goal.isNotBlank()

        val hasLegacyTargets =
            definition.targets.isNotEmpty()

        /*
         * New survey definitions use one interview goal.
         *
         * Legacy target-based definitions remain valid during migration so
         * existing surveys can still load until the old target architecture
         * is removed.
         */
        if (
            !hasGoal &&
            !hasLegacyTargets
        ) {
            errors +=
                "$questionId.followUp.goal must not be blank when follow-up " +
                        "is enabled unless legacy follow-up targets are provided."
        }

        /*
         * Target validation applies only to legacy target-based
         * configuration.
         */
        if (!hasLegacyTargets) {
            return
        }

        val targetIds =
            definition.targets.map {
                it.id
            }

        val duplicateTargetIds =
            findDuplicates(targetIds)

        if (duplicateTargetIds.isNotEmpty()) {
            errors +=
                "$questionId.followUp.targets contains duplicate IDs: " +
                        duplicateTargetIds.joinToString()
        }

        definition.targets.forEach { target ->
            if (target.id.isBlank()) {
                errors +=
                    "$questionId.followUp target ID must not be blank."
            }

            if (target.description.isBlank()) {
                errors +=
                    "$questionId.followUp target '${target.id}' " +
                            "description must not be blank."
            }

            if (target.maxAttempts <= 0) {
                errors +=
                    "$questionId.followUp target '${target.id}' " +
                            "maxAttempts must be greater than 0."
            }
        }
    }

    private fun validateNavigation(
        sourceNodeId: String,
        navigation: NavigationDefinition,
        nodesById: Map<String, SurveyNode>,
        errors: MutableList<String>,
    ) {
        val defaultNext =
            navigation.defaultNextNodeId

        /*
         * For the current Core V2 milestone every navigable node needs
         * either a default next node or at least one branch.
         */
        if (
            defaultNext == null &&
            navigation.branches.isEmpty()
        ) {
            errors +=
                "$sourceNodeId has no navigation target."

            return
        }

        if (defaultNext != null) {
            validateReference(
                sourceNodeId =
                    sourceNodeId,
                referenceName =
                    "next",
                targetNodeId =
                    defaultNext,
                nodesById =
                    nodesById,
                errors =
                    errors,
            )
        }

        navigation.branches.forEachIndexed {
                index,
                branch,
            ->

            validateReference(
                sourceNodeId =
                    sourceNodeId,
                referenceName =
                    "branches[$index].next",
                targetNodeId =
                    branch.nextNodeId,
                nodesById =
                    nodesById,
                errors =
                    errors,
            )

            if (
                branch.condition
                    .questionId
                    .isBlank()
            ) {
                errors +=
                    "$sourceNodeId.branches[$index].condition.questionId " +
                            "must not be blank."
            }
        }
    }

    private fun validateReference(
        sourceNodeId: String,
        referenceName: String,
        targetNodeId: String,
        nodesById: Map<String, SurveyNode>,
        errors: MutableList<String>,
    ) {
        if (targetNodeId.isBlank()) {
            errors +=
                "$sourceNodeId.$referenceName must not be blank."

            return
        }

        if (targetNodeId !in nodesById) {
            errors +=
                "$sourceNodeId.$referenceName references unknown node " +
                        "'$targetNodeId'."
        }
    }

    private fun validateReachability(
        startNodeId: String,
        nodesById: Map<String, SurveyNode>,
        errors: MutableList<String>,
    ) {
        val visited =
            mutableSetOf<String>()

        val pending =
            ArrayDeque<String>()

        pending.add(startNodeId)

        while (pending.isNotEmpty()) {
            val nodeId =
                pending.removeFirst()

            if (!visited.add(nodeId)) {
                continue
            }

            val node =
                nodesById[nodeId]
                    ?: continue

            outgoingNodeIds(node)
                .filter {
                    it in nodesById
                }
                .forEach {
                    pending.addLast(it)
                }
        }

        val unreachable =
            nodesById.keys
                .filter {
                    it !in visited
                }
                .sorted()

        if (unreachable.isNotEmpty()) {
            errors +=
                "Unreachable survey nodes: " +
                        unreachable.joinToString()
        }
    }

    private fun outgoingNodeIds(
        node: SurveyNode,
    ): List<String> {
        return when (node) {
            is StartNode ->
                listOf(
                    node.nextNodeId
                )

            is QuestionNode ->
                navigationTargets(
                    node.navigation
                )

            is ReviewNode ->
                navigationTargets(
                    node.navigation
                )

            is EndNode ->
                emptyList()
        }
    }

    private fun navigationTargets(
        navigation: NavigationDefinition,
    ): List<String> {
        return buildList {
            navigation.defaultNextNodeId
                ?.let {
                    add(it)
                }

            navigation.branches
                .forEach {
                    add(it.nextNodeId)
                }
        }
    }

    private fun findDuplicates(
        values: List<String>,
    ): List<String> {
        return values
            .groupingBy {
                it
            }
            .eachCount()
            .filterValues {
                it > 1
            }
            .keys
            .sorted()
    }
}

/**
 * Result returned by SurveyValidator.
 */
sealed interface SurveyValidationResult {

    data object Valid :
        SurveyValidationResult

    data class Invalid(
        val errors: List<String>,
    ) : SurveyValidationResult
}

/**
 * Thrown by SurveyValidator.requireValid().
 */
class SurveyDefinitionException(
    val errors: List<String>,
) : IllegalArgumentException(
    buildString {
        appendLine(
            "Survey definition is invalid:"
        )

        errors.forEach {
            appendLine(
                "- $it"
            )
        }
    }.trimEnd()
)