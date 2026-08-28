package com.negi.surveycore.survey.yaml

import com.negi.surveycore.survey.core.model.EndNode
import com.negi.surveycore.survey.core.model.QuestionNode
import com.negi.surveycore.survey.core.model.StartNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Integration tests for loading and validating the real Agriculture
 * Survey V2 YAML definition.
 *
 * These tests exercise SnakeYAML, SurveyYamlLoader, SurveyValidator,
 * and the Core V2 model without requiring Android, AI, or network access.
 */
class SurveyYamlLoaderTest {

    @Test
    fun agricultureYaml_loadsSuccessfully() {
        val definition =
            loadAgricultureSurvey()

        assertEquals(
            2,
            definition.schemaVersion,
        )

        assertEquals(
            "agriculture_maize",
            definition.metadata.id,
        )

        assertEquals(
            1,
            definition.metadata.version,
        )

        assertEquals(
            "Maize Farmer Survey",
            definition.metadata.title.default,
        )

        assertEquals(
            "en",
            definition.metadata.defaultLanguage,
        )

        assertEquals(
            listOf("en"),
            definition.metadata.supportedLanguages,
        )
    }

    @Test
    fun agricultureYaml_containsExpectedEightNodes() {
        val definition =
            loadAgricultureSurvey()

        /*
         * Expected graph:
         *
         * Start
         * Q1
         * Q2
         * Q3
         * Q4
         * Q5
         * Q6
         * Done
         */
        assertEquals(
            8,
            definition.flow.nodes.size,
        )

        assertEquals(
            "Start",
            definition.flow.startNodeId,
        )

        val nodeIds =
            definition.flow.nodes
                .map {
                    it.id
                }

        assertEquals(
            listOf(
                "Start",
                "Q1",
                "Q2",
                "Q3",
                "Q4",
                "Q5",
                "Q6",
                "Done",
            ),
            nodeIds,
        )
    }

    @Test
    fun agricultureYaml_hasCorrectNavigation() {
        val definition =
            loadAgricultureSurvey()

        val start =
            definition.flow.nodes
                .first {
                    it.id == "Start"
                }

        assertTrue(
            start is StartNode
        )

        start as StartNode

        assertEquals(
            "Q1",
            start.nextNodeId,
        )

        for (questionNumber in 1..5) {
            val currentId =
                "Q$questionNumber"

            val expectedNextId =
                "Q${questionNumber + 1}"

            val question =
                questionNode(
                    definition = definition,
                    id = currentId,
                )

            assertEquals(
                expectedNextId,
                question.navigation.defaultNextNodeId,
            )
        }

        val q6 =
            questionNode(
                definition = definition,
                id = "Q6",
            )

        assertEquals(
            "Done",
            q6.navigation.defaultNextNodeId,
        )

        val done =
            definition.flow.nodes
                .first {
                    it.id == "Done"
                }

        assertTrue(
            done is EndNode
        )
    }

    @Test
    fun agricultureYaml_q1HasExpectedResponseEvaluationConfiguration() {
        val definition =
            loadAgricultureSurvey()

        val q1 =
            questionNode(
                definition = definition,
                id = "Q1",
            )

        val semantic =
            q1.question
                .answer
                .semanticValidation

        /*
         * The Agriculture survey has migrated away from the legacy
         * criterion-by-criterion semantic validation path.
         */
        assertTrue(
            !semantic.enabled
        )

        assertTrue(
            semantic.goal.isBlank()
        )

        assertTrue(
            semantic.criteria.isEmpty()
        )

        assertEquals(
            0,
            semantic.maxClarifications,
        )

        val followUp =
            q1.question.followUp

        assertTrue(
            followUp.enabled
        )

        assertEquals(
            2,
            followUp.maxQuestions,
        )

        assertTrue(
            followUp.goal.contains(
                "fall armyworm",
                ignoreCase = true,
            )
        )

        assertTrue(
            followUp.goal.contains(
                "last three seasons",
                ignoreCase = true,
            )
        )

        /*
         * The new unified response-evaluation path uses one interview
         * goal rather than legacy per-target follow-up definitions.
         */
        assertTrue(
            followUp.targets.isEmpty()
        )
    }

    @Test
    fun agricultureYaml_allQuestionsUseUnifiedResponseEvaluation() {
        val definition =
            loadAgricultureSurvey()

        for (questionNumber in 1..6) {
            val question =
                questionNode(
                    definition = definition,
                    id = "Q$questionNumber",
                )

            val semantic =
                question.question
                    .answer
                    .semanticValidation

            assertTrue(
                "Legacy semantic validation must be disabled for Q$questionNumber",
                !semantic.enabled,
            )

            val followUp =
                question.question.followUp

            assertTrue(
                "Unified response evaluation must be enabled for Q$questionNumber",
                followUp.enabled,
            )

            assertTrue(
                "Interview goal must not be blank for Q$questionNumber",
                followUp.goal.isNotBlank(),
            )

            assertEquals(
                "Each Agriculture question should allow two follow-up questions",
                2,
                followUp.maxQuestions,
            )

            assertTrue(
                "Legacy follow-up targets must be empty for Q$questionNumber",
                followUp.targets.isEmpty(),
            )
        }
    }

    @Test
    fun agricultureYaml_interviewerInstructionSupportsUnifiedFollowUpBehavior() {
        val definition =
            loadAgricultureSurvey()

        val instruction =
            definition.interviewer
                .instruction
                .default

        assertTrue(
            instruction.contains(
                "follow-up",
                ignoreCase = true,
            )
        )

        assertTrue(
            instruction.contains(
                "Do not invent",
                ignoreCase = true,
            )
        )

        assertTrue(
            instruction.contains(
                "Do not ask for information that has already been provided",
                ignoreCase = true,
            )
        )
    }

    @Test
    fun agricultureYaml_passesSurveyValidator() {
        val definition =
            loadAgricultureSurvey()

        val result =
            SurveyValidator.validate(
                definition
            )

        assertTrue(
            when (result) {
                SurveyValidationResult.Valid ->
                    true

                is SurveyValidationResult.Invalid -> {
                    println(
                        result.errors.joinToString(
                            separator = "\n"
                        )
                    )

                    false
                }
            }
        )
    }

    @Test
    fun agricultureYaml_requireValidReturnsSameDefinition() {
        val definition =
            loadAgricultureSurvey()

        val validated =
            SurveyValidator.requireValid(
                definition
            )

        assertEquals(
            definition,
            validated,
        )
    }

    private fun questionNode(
        definition:
        com.negi.surveycore.survey.core.model.SurveyDefinition,
        id: String,
    ): QuestionNode {
        val node =
            definition.flow.nodes
                .firstOrNull {
                    it.id == id
                }

        assertNotNull(
            "Missing question node $id",
            node,
        )

        assertTrue(
            "Node $id must be a QuestionNode",
            node is QuestionNode,
        )

        return node as QuestionNode
    }

    /**
     * Finds the YAML file whether Gradle executes the JVM test from
     * the repository root or from the app module directory.
     */
    private fun loadAgricultureSurvey():
            com.negi.surveycore.survey.core.model.SurveyDefinition {

        val relativePath =
            "src/main/assets/surveys/agriculture_maize_v2.yaml"

        val candidates =
            listOf(
                File(relativePath),
                File(
                    "app/$relativePath"
                ),
            )

        val yamlFile =
            candidates.firstOrNull {
                it.isFile
            }
                ?: error(
                    buildString {
                        appendLine(
                            "Agriculture YAML file was not found."
                        )

                        appendLine(
                            "Working directory: ${File(".").absolutePath}"
                        )

                        appendLine(
                            "Checked:"
                        )

                        candidates.forEach {
                            appendLine(
                                "- ${it.absolutePath}"
                            )
                        }
                    }
                )

        return yamlFile
            .inputStream()
            .use {
                SurveyYamlLoader.load(it)
            }
    }
}