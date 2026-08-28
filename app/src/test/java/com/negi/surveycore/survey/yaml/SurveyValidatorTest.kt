package com.negi.surveycore.survey.yaml

import com.negi.surveycore.survey.core.model.EndNode
import com.negi.surveycore.survey.core.model.FollowUpDefinition
import com.negi.surveycore.survey.core.model.FollowUpTargetDefinition
import com.negi.surveycore.survey.core.model.QuestionNode
import com.negi.surveycore.survey.core.model.SemanticValidationDefinition
import com.negi.surveycore.survey.core.model.SurveyDefinition
import com.negi.surveycore.survey.reference.agriculture.AgricultureSurvey
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Negative regression tests for SurveyValidator.
 *
 * Each test starts from a known-valid Agriculture Survey definition,
 * introduces one intentional structural or configuration error, and
 * verifies that SurveyValidator rejects it.
 *
 * These tests require no Android runtime, AI model, or network access.
 */
class SurveyValidatorTest {

    @Test
    fun duplicateNodeId_isRejected() {
        val valid =
            validSurvey()

        val q1 =
            questionNode(
                definition = valid,
                id = "Q1",
            )

        val invalid =
            valid.copy(
                flow =
                    valid.flow.copy(
                        nodes =
                            valid.flow.nodes +
                                    q1.copy(),
                    ),
            )

        assertInvalidContains(
            definition = invalid,
            expectedText = "Duplicate node IDs",
        )
    }

    @Test
    fun unknownNextNode_isRejected() {
        val valid =
            validSurvey()

        val invalidNodes =
            valid.flow.nodes.map { node ->
                if (
                    node is QuestionNode &&
                    node.id == "Q1"
                ) {
                    node.copy(
                        navigation =
                            node.navigation.copy(
                                defaultNextNodeId =
                                    "MissingNode",
                            ),
                    )
                } else {
                    node
                }
            }

        val invalid =
            valid.copy(
                flow =
                    valid.flow.copy(
                        nodes = invalidNodes,
                    ),
            )

        assertInvalidContains(
            definition = invalid,
            expectedText =
                "references unknown node 'MissingNode'",
        )
    }

    @Test
    fun missingEndNode_isRejected() {
        val valid =
            validSurvey()

        val invalid =
            valid.copy(
                flow =
                    valid.flow.copy(
                        nodes =
                            valid.flow.nodes.filterNot {
                                it is EndNode
                            },
                    ),
            )

        assertInvalidContains(
            definition = invalid,
            expectedText =
                "Survey must contain at least one end node",
        )
    }

    @Test
    fun unreachableNode_isRejected() {
        val valid =
            validSurvey()

        /*
         * Add a valid terminal node that is never referenced by any
         * navigation path.
         */
        val unreachableNode =
            EndNode(
                id = "UnusedEnd",
            )

        val invalid =
            valid.copy(
                flow =
                    valid.flow.copy(
                        nodes =
                            valid.flow.nodes +
                                    unreachableNode,
                    ),
            )

        assertInvalidContains(
            definition = invalid,
            expectedText =
                "Unreachable survey nodes: UnusedEnd",
        )
    }

    @Test
    fun defaultLanguageNotSupported_isRejected() {
        val valid =
            validSurvey()

        val invalid =
            valid.copy(
                metadata =
                    valid.metadata.copy(
                        defaultLanguage = "en",
                        supportedLanguages =
                            listOf("sw"),
                    ),
            )

        assertInvalidContains(
            definition = invalid,
            expectedText =
                "must be included in supportedLanguages",
        )
    }

    @Test
    fun invalidSemanticValidation_isRejected() {
        val valid =
            validSurvey()

        val q1 =
            questionNode(
                definition = valid,
                id = "Q1",
            )

        val invalidSemantic =
            SemanticValidationDefinition(
                enabled = true,
                goal = "",
                criteria = emptyList(),
                maxClarifications = -1,
            )

        val invalidQ1 =
            q1.copy(
                question =
                    q1.question.copy(
                        answer =
                            q1.question.answer.copy(
                                semanticValidation =
                                    invalidSemantic,
                            ),
                    ),
            )

        val invalid =
            replaceQuestion(
                definition = valid,
                replacement = invalidQ1,
            )

        val errors =
            invalidErrors(invalid)

        assertContains(
            errors = errors,
            expectedText =
                "maxClarifications must be >= 0",
        )

        assertContains(
            errors = errors,
            expectedText =
                "goal must not be blank",
        )

        assertContains(
            errors = errors,
            expectedText =
                "criteria must not be empty",
        )
    }

    @Test
    fun invalidFollowUpConfiguration_isRejected() {
        val valid =
            validSurvey()

        val q1 =
            questionNode(
                definition = valid,
                id = "Q1",
            )

        val invalidFollowUp =
            FollowUpDefinition(
                enabled = true,
                maxQuestions = 0,
                targets =
                    listOf(
                        FollowUpTargetDefinition(
                            id = "target_one",
                            description = "",
                            maxAttempts = 0,
                        )
                    ),
            )

        val invalidQ1 =
            q1.copy(
                question =
                    q1.question.copy(
                        followUp =
                            invalidFollowUp,
                    ),
            )

        val invalid =
            replaceQuestion(
                definition = valid,
                replacement = invalidQ1,
            )

        val errors =
            invalidErrors(invalid)

        assertContains(
            errors = errors,
            expectedText =
                "maxQuestions must be greater than 0",
        )

        assertContains(
            errors = errors,
            expectedText =
                "description must not be blank",
        )

        assertContains(
            errors = errors,
            expectedText =
                "maxAttempts must be greater than 0",
        )
    }

    @Test
    fun enabledFollowUpWithoutGoalOrLegacyTargets_isRejected() {
        val valid =
            validSurvey()

        val q1 =
            questionNode(
                definition = valid,
                id = "Q1",
            )

        val invalidQ1 =
            q1.copy(
                question =
                    q1.question.copy(
                        followUp =
                            FollowUpDefinition(
                                enabled = true,
                                maxQuestions = 2,
                                targets = emptyList(),
                                goal = "",
                            ),
                    ),
            )

        val invalid =
            replaceQuestion(
                definition = valid,
                replacement = invalidQ1,
            )

        assertInvalidContains(
            definition = invalid,
            expectedText =
                "followUp.goal must not be blank",
        )
    }

    @Test
    fun goalBasedFollowUpWithoutLegacyTargets_isValid() {
        val valid =
            validSurvey()

        val q1 =
            questionNode(
                definition = valid,
                id = "Q1",
            )

        val updatedQ1 =
            q1.copy(
                question =
                    q1.question.copy(
                        followUp =
                            FollowUpDefinition(
                                enabled = true,
                                maxQuestions = 2,
                                targets = emptyList(),
                                goal =
                                    "Understand the respondent's answer well enough " +
                                            "to resolve important missing information.",
                            ),
                    ),
            )

        val updated =
            replaceQuestion(
                definition = valid,
                replacement = updatedQ1,
            )

        val result =
            SurveyValidator.validate(
                updated
            )

        assertTrue(
            buildString {
                appendLine(
                    "Expected goal-based follow-up configuration to be valid."
                )

                if (
                    result is SurveyValidationResult.Invalid
                ) {
                    result.errors.forEach {
                        appendLine(
                            "- $it"
                        )
                    }
                }
            },
            result is SurveyValidationResult.Valid,
        )
    }

    @Test
    fun requireValid_throwsForInvalidSurvey() {
        val valid =
            validSurvey()

        val invalid =
            valid.copy(
                schemaVersion = 999,
            )

        try {
            SurveyValidator.requireValid(
                invalid
            )

            fail(
                "Expected SurveyDefinitionException."
            )
        } catch (
            exception: SurveyDefinitionException
        ) {
            assertTrue(
                exception.errors.any {
                    it.contains(
                        "Unsupported schemaVersion"
                    )
                }
            )
        }
    }

    private fun validSurvey():
            SurveyDefinition {
        return AgricultureSurvey
            .createFullReferenceSurvey()
    }

    private fun questionNode(
        definition: SurveyDefinition,
        id: String,
    ): QuestionNode {
        val node =
            definition.flow.nodes
                .firstOrNull {
                    it.id == id
                }
                ?: error(
                    "Question node not found: $id"
                )

        check(node is QuestionNode) {
            "Node $id is not a QuestionNode."
        }

        return node
    }

    private fun replaceQuestion(
        definition: SurveyDefinition,
        replacement: QuestionNode,
    ): SurveyDefinition {
        return definition.copy(
            flow =
                definition.flow.copy(
                    nodes =
                        definition.flow.nodes.map {
                                node ->

                            if (
                                node.id ==
                                replacement.id
                            ) {
                                replacement
                            } else {
                                node
                            }
                        },
                ),
        )
    }

    private fun assertInvalidContains(
        definition: SurveyDefinition,
        expectedText: String,
    ) {
        val errors =
            invalidErrors(
                definition
            )

        assertContains(
            errors = errors,
            expectedText =
                expectedText,
        )
    }

    private fun invalidErrors(
        definition: SurveyDefinition,
    ): List<String> {
        return when (
            val result =
                SurveyValidator.validate(
                    definition
                )
        ) {
            SurveyValidationResult.Valid -> {
                fail(
                    "Expected survey to be invalid."
                )

                emptyList()
            }

            is SurveyValidationResult.Invalid ->
                result.errors
        }
    }

    private fun assertContains(
        errors: List<String>,
        expectedText: String,
    ) {
        assertTrue(
            buildString {
                appendLine(
                    "Expected validation error containing:"
                )

                appendLine(
                    expectedText
                )

                appendLine(
                    "Actual errors:"
                )

                errors.forEach {
                    appendLine(
                        "- $it"
                    )
                }
            },
            errors.any {
                it.contains(
                    expectedText,
                    ignoreCase = false,
                )
            },
        )
    }
}