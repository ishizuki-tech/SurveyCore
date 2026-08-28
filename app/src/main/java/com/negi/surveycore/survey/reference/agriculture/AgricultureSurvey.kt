package com.negi.surveycore.survey.reference.agriculture

import com.negi.surveycore.survey.core.model.AnswerDefinition
import com.negi.surveycore.survey.core.model.AnswerType
import com.negi.surveycore.survey.core.model.DeterministicValidationDefinition
import com.negi.surveycore.survey.core.model.EndNode
import com.negi.surveycore.survey.core.model.FollowUpDefinition
import com.negi.surveycore.survey.core.model.InputMode
import com.negi.surveycore.survey.core.model.InterviewerDefinition
import com.negi.surveycore.survey.core.model.LocalizedText
import com.negi.surveycore.survey.core.model.NavigationDefinition
import com.negi.surveycore.survey.core.model.QuestionDefinition
import com.negi.surveycore.survey.core.model.QuestionNode
import com.negi.surveycore.survey.core.model.SemanticValidationDefinition
import com.negi.surveycore.survey.core.model.StartNode
import com.negi.surveycore.survey.core.model.SurveyDefinition
import com.negi.surveycore.survey.core.model.SurveyFlowDefinition
import com.negi.surveycore.survey.core.model.SurveyMetadata

/**
 * Kotlin-defined reference Agriculture Survey.
 *
 * The Q1-only definition remains available for the original vertical-slice
 * regression tests. The full reference definition contains Q1 through Q6.
 *
 * Research-oriented follow-up is intentionally disabled at this stage.
 * A8 focuses on deterministic navigation and semantic validation.
 */
object AgricultureSurvey {

    /**
     * Original Q1-only survey used by the A6/A7 regression tests.
     */
    fun createQ1ReferenceSurvey(): SurveyDefinition {
        return createSurvey(
            nodes =
                listOf(
                    StartNode(
                        id = "Start",
                        nextNodeId = "Q1",
                    ),
                    createQ1(
                        nextNodeId = "Done",
                    ),
                    createDoneNode(),
                )
        )
    }

    /**
     * Full Agriculture reference survey used from A8 onward.
     */
    fun createFullReferenceSurvey(): SurveyDefinition {
        return createSurvey(
            nodes =
                listOf(
                    StartNode(
                        id = "Start",
                        nextNodeId = "Q1",
                    ),
                    createQ1(
                        nextNodeId = "Q2",
                    ),
                    createQ2(
                        nextNodeId = "Q3",
                    ),
                    createQ3(
                        nextNodeId = "Q4",
                    ),
                    createQ4(
                        nextNodeId = "Q5",
                    ),
                    createQ5(
                        nextNodeId = "Q6",
                    ),
                    createQ6(
                        nextNodeId = "Done",
                    ),
                    createDoneNode(),
                )
        )
    }

    private fun createSurvey(
        nodes: List<com.negi.surveycore.survey.core.model.SurveyNode>,
    ): SurveyDefinition {
        return SurveyDefinition(
            schemaVersion = 2,

            metadata =
                SurveyMetadata(
                    id = "agriculture_maize",
                    version = 1,
                    title =
                        LocalizedText(
                            default = "Maize Farmer Survey",
                        ),
                    defaultLanguage = "en",
                    supportedLanguages =
                        listOf("en"),
                ),

            interviewer =
                InterviewerDefinition(
                    instruction =
                        LocalizedText(
                            default =
                                """
                                You are a professional and neutral agricultural
                                survey interviewer.

                                Ask concise questions.
                                Do not invent information about the respondent.
                                Ask only for information required by the survey.
                                """.trimIndent(),
                        ),
                ),

            flow =
                SurveyFlowDefinition(
                    startNodeId = "Start",
                    nodes = nodes,
                ),
        )
    }

    /**
     * Q1 — Fall armyworm yield loss.
     */
    private fun createQ1(
        nextNodeId: String,
    ): QuestionNode {
        return createQuestion(
            id = "Q1",
            title = "FAW Yield Loss (3 Seasons)",

            prompt =
                """
                How much yield do you lose because of fall armyworm?
                Please think back over the last 3 seasons.
                Percent or bags per acre are fine.
                """.trimIndent(),

            validationGoal =
                """
                Obtain the respondent's average fall armyworm yield loss
                over the last three seasons using a clear measurable unit.
                """.trimIndent(),

            criteria =
                listOf(
                    "A yield-loss magnitude is provided.",
                    "The unit is clear, such as percent or bags per acre.",
                    "The answer represents the last three seasons or an average across them.",
                ),

            nextNodeId = nextNodeId,
        )
    }

    /**
     * Q2 — Trade-off for harvesting ten days earlier.
     */
    private fun createQ2(
        nextNodeId: String,
    ): QuestionNode {
        return createQuestion(
            id = "Q2",
            title = "Trade-off for 10-Day Earlier Harvest",

            prompt =
                """
                If you could harvest 10 days earlier, how much yield
                would you be willing to give up?
                Percent or bags per acre are fine.
                """.trimIndent(),

            validationGoal =
                """
                Obtain the maximum yield loss the respondent would accept
                in exchange for harvesting ten days earlier, expressed
                using a clear measurable unit.
                """.trimIndent(),

            criteria =
                listOf(
                    "A maximum acceptable yield-loss magnitude is provided.",
                    "The unit is clear, such as percent or bags per acre.",
                    "The answer clearly refers to the trade-off for harvesting 10 days earlier.",
                ),

            nextNodeId = nextNodeId,
        )
    }

    /**
     * Q3 — Pest or disease damage threshold for changing variety.
     */
    private fun createQ3(
        nextNodeId: String,
    ): QuestionNode {
        return createQuestion(
            id = "Q3",
            title = "Damage Threshold to Switch Variety",

            prompt =
                """
                At what level of pest or disease damage would you decide
                to switch to a different maize variety?
                Please give a clear threshold, for example percent of
                plants or ears affected, severity, and crop stage.
                """.trimIndent(),

            validationGoal =
                """
                Obtain a clear damage threshold that would cause the
                respondent to switch maize variety, including a measurable
                metric, threshold level, and relevant crop stage.
                """.trimIndent(),

            criteria =
                listOf(
                    "A measurable damage metric is identified.",
                    "A clear threshold level is provided.",
                    "The relevant crop stage or timing is clear.",
                ),

            nextNodeId = nextNodeId,
        )
    }

    /**
     * Q4 — Desired characteristics of a replacement variety.
     */
    private fun createQ4(
        nextNodeId: String,
    ): QuestionNode {
        return createQuestion(
            id = "Q4",
            title = "Expectations for Replacement Variety",

            prompt =
                """
                If you replaced your current maize variety, what would you
                expect from the new variety?
                Please think about things such as days to maturity,
                fall armyworm tolerance, drought tolerance at specific
                crop stages, and typical yield.
                """.trimIndent(),

            validationGoal =
                """
                Obtain the respondent's priorities for a replacement maize
                variety, including at least one measurable target related
                to maturity, fall armyworm tolerance, drought tolerance,
                or yield.
                """.trimIndent(),

            criteria =
                listOf(
                    "At least one replacement-variety priority is identified.",
                    "At least one priority includes a measurable target.",
                    "The measurable target is connected to maturity, FAW tolerance, drought tolerance, or yield.",
                ),

            nextNodeId = nextNodeId,
        )
    }

    /**
     * Q5 — Minimum acceptable harvest in a bad year.
     */
    private fun createQ5(
        nextNodeId: String,
    ): QuestionNode {
        return createQuestion(
            id = "Q5",
            title = "Minimum Acceptable Harvest in Bad Year",

            prompt =
                """
                In a bad year, what is the smallest harvest you would
                accept and still plant the same maize variety next season?
                You can answer as a percent of your usual harvest or
                in bags per acre.
                """.trimIndent(),

            validationGoal =
                """
                Obtain the respondent's minimum acceptable bad-year
                harvest while still being willing to plant the same maize
                variety next season, with a clear baseline or unit.
                """.trimIndent(),

            criteria =
                listOf(
                    "A minimum acceptable harvest magnitude is provided.",
                    "The baseline or unit is clear, such as percent of usual harvest or bags per acre.",
                    "The answer represents the threshold for still planting the same variety next season.",
                ),

            nextNodeId = nextNodeId,
        )
    }

    /**
     * Q6 — Crop stage at which drought causes the most damage.
     */
    private fun createQ6(
        nextNodeId: String,
    ): QuestionNode {
        return createQuestion(
            id = "Q6",
            title = "Most Damaging Drought Timing",

            prompt =
                """
                At what stage of maize growth is drought most damaging
                for you?
                For example, before tasseling, during tasseling or silking,
                during grain fill, or before harvest.
                Please briefly explain why.
                """.trimIndent(),

            validationGoal =
                """
                Identify the maize crop stage at which drought is most
                damaging for the respondent and obtain a brief reason
                explaining why that stage is especially damaging.
                """.trimIndent(),

            criteria =
                listOf(
                    "A specific crop stage or timing is identified.",
                    "A brief reason for why drought at that stage is most damaging is provided.",
                ),

            nextNodeId = nextNodeId,
        )
    }

    /**
     * Creates a standard free-text agriculture question.
     *
     * The helper keeps common runtime behavior identical across all
     * reference questions while allowing each question to define its own
     * semantic goal and criteria.
     */
    private fun createQuestion(
        id: String,
        title: String,
        prompt: String,
        validationGoal: String,
        criteria: List<String>,
        nextNodeId: String,
    ): QuestionNode {
        return QuestionNode(
            id = id,

            title =
                LocalizedText(
                    default = title,
                ),

            question =
                QuestionDefinition(
                    prompt =
                        LocalizedText(
                            default = prompt,
                        ),

                    answer =
                        AnswerDefinition(
                            type = AnswerType.TEXT,
                            required = true,

                            inputModes =
                                setOf(
                                    InputMode.TEXT,
                                ),

                            deterministicValidation =
                                DeterministicValidationDefinition(),

                            semanticValidation =
                                SemanticValidationDefinition(
                                    enabled = true,
                                    goal = validationGoal,
                                    criteria = criteria,
                                    maxClarifications = 2,
                                ),
                        ),

                    /*
                     * Research follow-up remains disabled during A8.
                     * Semantic answer validation is the only AI-assisted
                     * behavior exercised at this milestone.
                     */
                    followUp =
                        FollowUpDefinition.disabled(),
                ),

            navigation =
                NavigationDefinition(
                    defaultNextNodeId = nextNodeId,
                ),
        )
    }

    private fun createDoneNode(): EndNode {
        return EndNode(
            id = "Done",
            completionMessage =
                LocalizedText(
                    default =
                        "Thank you. The survey is complete.",
                ),
        )
    }
}