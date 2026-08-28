package com.negi.surveycore.ai.prompt

import com.negi.surveycore.survey.core.ai.ResponseEvaluationRequest
import com.negi.surveycore.survey.core.ai.ResponseFollowUpExchange
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseEvaluationPromptBuilderTest {

    @Test
    fun build_withoutFollowUpHistory_containsRequiredContextAndProtocol() {
        val prompt =
            ResponseEvaluationPromptBuilder.build(
                createRequest()
            )

        assertTrue(
            prompt.contains(
                "SURVEY QUESTION"
            )
        )

        assertTrue(
            prompt.contains(
                "How much yield do you lose because of fall armyworm?"
            )
        )

        assertTrue(
            prompt.contains(
                "INTERVIEW GOAL"
            )
        )

        assertTrue(
            prompt.contains(
                "Understand the magnitude, unit, and recent-season context."
            )
        )

        assertTrue(
            prompt.contains(
                "ORIGINAL RESPONDENT ANSWER"
            )
        )

        assertTrue(
            prompt.contains(
                "20"
            )
        )

        assertTrue(
            prompt.contains(
                "FOLLOW-UP HISTORY"
            )
        )

        assertTrue(
            prompt.contains(
                "STATUS: DONE|FOLLOW_UP"
            )
        )

        assertTrue(
            prompt.contains(
                "REMAINING_GAP: <short description or NONE>"
            )
        )

        assertTrue(
            prompt.contains(
                "QUESTION: <one follow-up question or NONE>"
            )
        )

        assertTrue(
            prompt.contains(
                "SUFFICIENCY: <one integer>"
            )
        )
    }

    @Test
    fun build_withFollowUpHistory_serializesCompleteConversationAsTranscript() {
        val prompt =
            ResponseEvaluationPromptBuilder.build(
                createRequest(
                    previousFollowUps =
                        listOf(
                            ResponseFollowUpExchange(
                                question =
                                    "What unit does 20 represent?",
                                answer =
                                    "percent",
                            ),
                            ResponseFollowUpExchange(
                                question =
                                    "Is that representative of the last three seasons?",
                                answer =
                                    "Yes",
                            ),
                        ),
                )
            )

        assertTrue(
            prompt.contains(
                "RESPONDENT EVIDENCE TRANSCRIPT"
            )
        )

        val transcript =
            prompt
                .substringAfter(
                    "RESPONDENT EVIDENCE TRANSCRIPT"
                )
                .substringBefore(
                    "OUTPUT CONTRACT"
                )

        val originalAnswerIndex =
            transcript.indexOf(
                "RESPONDENT:\n20"
            )

        val firstQuestionIndex =
            transcript.indexOf(
                "INTERVIEWER:\nWhat unit does 20 represent?"
            )

        val firstAnswerIndex =
            transcript.indexOf(
                "RESPONDENT:\npercent"
            )

        val secondQuestionIndex =
            transcript.indexOf(
                "INTERVIEWER:\nIs that representative of the last three seasons?"
            )

        val secondAnswerIndex =
            transcript.indexOf(
                "RESPONDENT:\nYes"
            )

        assertTrue(
            originalAnswerIndex >= 0
        )

        assertTrue(
            firstQuestionIndex > originalAnswerIndex
        )

        assertTrue(
            firstAnswerIndex > firstQuestionIndex
        )

        assertTrue(
            secondQuestionIndex > firstAnswerIndex
        )

        assertTrue(
            secondAnswerIndex > secondQuestionIndex
        )
    }

    @Test
    fun build_distinguishesQuestionContextFromRespondentSelectedFacts() {
        val prompt =
            ResponseEvaluationPromptBuilder.build(
                createRequest()
            )

        assertTrue(
            prompt.contains(
                "SURVEY QUESTION establishes the topic and context",
                ignoreCase = false,
            )
        )

        assertTrue(
            prompt.contains(
                "requested time period",
                ignoreCase = false,
            )
        )

        assertTrue(
            prompt.contains(
                "to repeat that context",
                ignoreCase = false,
            )
        )

        assertTrue(
            prompt.contains(
                "suggested units",
                ignoreCase = false,
            )
        )

        assertTrue(
            prompt.contains(
                "are not respondent-selected facts",
                ignoreCase = false,
            )
        )
    }

    @Test
    fun build_requiresAmbiguousBareNumberToRemainAmbiguousWhenMultipleUnitsAreOffered() {
        val prompt =
            ResponseEvaluationPromptBuilder.build(
                createRequest()
            )

        assertTrue(
            prompt.contains(
                "a bare number does",
                ignoreCase = false,
            )
        )

        assertTrue(
            prompt.contains(
                "not identify which one the respondent means",
                ignoreCase = false,
            )
        )
    }

    @Test
    fun build_requiresGapDecisionBeforeOtherProtocolFields() {
        val prompt =
            ResponseEvaluationPromptBuilder.build(
                createRequest()
            )

        assertTrue(
            prompt.contains(
                "Identify the most important required REMAINING_GAP first.",
                ignoreCase = false,
            )
        )

        assertTrue(
            prompt.contains(
                "assign SUFFICIENCY last.",
                ignoreCase = false,
            )
        )

        val contract =
            prompt.substringAfter(
                "OUTPUT CONTRACT"
            )

        val gapIndex =
            contract.indexOf(
                "REMAINING_GAP: <short description or NONE>"
            )

        val statusIndex =
            contract.indexOf(
                "STATUS: DONE|FOLLOW_UP"
            )

        val questionIndex =
            contract.indexOf(
                "QUESTION: <one follow-up question or NONE>"
            )

        val sufficiencyIndex =
            contract.indexOf(
                "SUFFICIENCY: <one integer>"
            )

        assertTrue(
            gapIndex >= 0
        )

        assertTrue(
            statusIndex > gapIndex
        )

        assertTrue(
            questionIndex > statusIndex
        )

        assertTrue(
            sufficiencyIndex > questionIndex
        )
    }

    @Test
    fun build_requiresFollowUpQuestionToRemainGroundedInRespondentEvidence() {
        val prompt =
            ResponseEvaluationPromptBuilder.build(
                createRequest()
            )

        assertTrue(
            prompt.contains(
                "A follow-up QUESTION must ask only for the remaining missing information",
                ignoreCase = false,
            )
        )

        assertTrue(
            prompt.contains(
                "Preserve ambiguous respondent evidence as given.",
                ignoreCase = false,
            )
        )

        assertTrue(
            prompt.contains(
                "If the respondent explicitly supplies a value together with a unit",
                ignoreCase = false,
            )
        )

        assertTrue(
            prompt.contains(
                "treat that information as present evidence",
                ignoreCase = false,
            )
        )

        assertTrue(
            prompt.contains(
                "without adding the missing information.",
                ignoreCase = false,
            )
        )
    }

    @Test
    fun build_withoutFollowUpHistory_keepsOriginalFirstTurnEvidenceFormat() {
        val prompt =
            ResponseEvaluationPromptBuilder.build(
                createRequest()
            )

        assertTrue(
            prompt.contains(
                "ORIGINAL RESPONDENT ANSWER\n20"
            )
        )

        assertTrue(
            prompt.contains(
                "FOLLOW-UP HISTORY\nNONE"
            )
        )

        assertFalse(
            prompt.contains(
                "RESPONDENT EVIDENCE TRANSCRIPT"
            )
        )

        assertFalse(
            prompt.contains(
                "Do not evaluate the original answer in isolation."
            )
        )
    }

    @Test
    fun build_withFollowUpHistory_evaluatesTranscriptAsAccumulatedResponse() {
        val prompt =
            ResponseEvaluationPromptBuilder.build(
                createRequest(
                    previousFollowUps =
                        listOf(
                            ResponseFollowUpExchange(
                                question =
                                    "What unit does 20 represent?",
                                answer =
                                    "percent",
                            )
                        ),
                )
            )

        assertTrue(
            prompt.contains(
                "Evaluate the complete transcript above as one accumulated response."
            )
        )

        assertTrue(
            prompt.contains(
                "Do not evaluate the original answer in isolation."
            )
        )

        assertFalse(
            prompt.contains(
                "FOLLOW-UP 1 QUESTION"
            )
        )

        assertFalse(
            prompt.contains(
                "LATEST FOLLOW-UP TO RESOLVE"
            )
        )
    }

    @Test
    fun build_withoutFollowUpHistory_doesNotAddMultiTurnGapConsistencyRules() {
        val prompt =
            ResponseEvaluationPromptBuilder.build(
                createRequest()
            )

        assertFalse(
            prompt.contains(
                "REMAINING_GAP describes only information that still remains missing or ambiguous after"
            )
        )

        assertFalse(
            prompt.contains(
                "Never put a resolved, historical, or already answered gap in REMAINING_GAP."
            )
        )

        assertFalse(
            prompt.contains(
                "If STATUS is DONE, REMAINING_GAP must be exactly NONE and QUESTION must be exactly NONE."
            )
        )
    }

    @Test
    fun build_withFollowUpHistory_requiresOnlyRemainingGapToBeReported() {
        val prompt =
            ResponseEvaluationPromptBuilder.build(
                createRequest(
                    previousFollowUps =
                        listOf(
                            ResponseFollowUpExchange(
                                question =
                                    "Do you mean 20 percent or 20 bags per acre?",
                                answer =
                                    "percent",
                            )
                        ),
                )
            )

        assertTrue(
            prompt.contains(
                "REMAINING_GAP describes only information that still remains missing or ambiguous after"
            )
        )

        assertTrue(
            prompt.contains(
                "considering the complete respondent evidence transcript."
            )
        )

        assertTrue(
            prompt.contains(
                "Never put a resolved, historical, or already answered gap in REMAINING_GAP."
            )
        )

        assertTrue(
            prompt.contains(
                "If STATUS is DONE, REMAINING_GAP must be exactly NONE and QUESTION must be exactly NONE."
            )
        )
    }

    @Test
    fun build_withFollowUpHistory_placesGapConsistencyRulesInsideFinalCheck() {
        val prompt =
            ResponseEvaluationPromptBuilder.build(
                createRequest(
                    previousFollowUps =
                        listOf(
                            ResponseFollowUpExchange(
                                question =
                                    "Do you mean 20 percent or 20 bags per acre?",
                                answer =
                                    "percent",
                            )
                        ),
                )
            )

        val finalCheckIndex =
            prompt.indexOf(
                "Final consistency check:"
            )

        val remainingGapRuleIndex =
            prompt.indexOf(
                "REMAINING_GAP describes only information that still remains missing or ambiguous after"
            )

        val exactDoneRuleIndex =
            prompt.indexOf(
                "If STATUS is DONE, REMAINING_GAP must be exactly NONE and QUESTION must be exactly NONE."
            )

        assertTrue(
            finalCheckIndex >= 0
        )

        assertTrue(
            remainingGapRuleIndex > finalCheckIndex
        )

        assertTrue(
            exactDoneRuleIndex > remainingGapRuleIndex
        )
    }

    @Test
    fun build_requiresGapToBeResolvedFromInterviewGoalBeforeDone() {
        val prompt =
            ResponseEvaluationPromptBuilder.build(
                createRequest()
            )

        assertTrue(
            prompt.contains(
                "compare the accumulated respondent evidence with each required part of the INTERVIEW GOAL",
                ignoreCase = false,
            )
        )

        assertTrue(
            prompt.contains(
                "If any required part is still missing or ambiguous, REMAINING_GAP must not be NONE.",
                ignoreCase = false,
            )
        )

        assertTrue(
            prompt.contains(
                "Use REMAINING_GAP: NONE only after confirming that no important required gap remains.",
                ignoreCase = false,
            )
        )

        assertTrue(
            prompt.contains(
                "Choose DONE only when REMAINING_GAP is NONE.",
                ignoreCase = false,
            )
        )
    }

    @Test
    fun build_requiresSingleIntegerSufficiency() {
        val prompt =
            ResponseEvaluationPromptBuilder.build(
                createRequest()
            )

        assertTrue(
            prompt.contains(
                "Output one integer from 0 through 100.",
                ignoreCase = false,
            )
        )

        assertTrue(
            prompt.contains(
                "Never output a range or explanation.",
                ignoreCase = false,
            )
        )

        assertTrue(
            prompt.contains(
                "FOLLOW_UP requires a score of 80 or lower.",
                ignoreCase = false,
            )
        )

        assertTrue(
            prompt.contains(
                "DONE requires a score of 81 or higher.",
                ignoreCase = false,
            )
        )

        assertFalse(
            prompt.contains(
                "SUFFICIENCY: 81-99",
                ignoreCase = false,
            )
        )
    }

    @Test
    fun build_requiresConsistentProtocolFields() {
        val prompt =
            ResponseEvaluationPromptBuilder.build(
                createRequest()
            )

        assertTrue(
            prompt.contains(
                "REMAINING_GAP: NONE requires STATUS: DONE and QUESTION: NONE.",
                ignoreCase = false,
            )
        )

        assertTrue(
            prompt.contains(
                "A real REMAINING_GAP requires STATUS: FOLLOW_UP and one real QUESTION.",
                ignoreCase = false,
            )
        )

        assertTrue(
            prompt.contains(
                "Never output DONE with a real REMAINING_GAP or QUESTION.",
                ignoreCase = false,
            )
        )
    }

    @Test
    fun build_outputContract_doesNotUseLegacyGapLabel() {
        val prompt =
            ResponseEvaluationPromptBuilder.build(
                createRequest()
            )

        val contract =
            prompt.substringAfter(
                "OUTPUT CONTRACT"
            )

        assertFalse(
            contract
                .lineSequence()
                .any {
                    it.trim().startsWith(
                        "GAP:"
                    )
                }
        )
    }

    @Test
    fun build_doesNotIncludeRuntimeSpecificModelInformation() {
        val prompt =
            ResponseEvaluationPromptBuilder.build(
                createRequest()
            )

        assertFalse(
            prompt.contains(
                "Qwen",
                ignoreCase = true,
            )
        )

        assertFalse(
            prompt.contains(
                "Gemma",
                ignoreCase = true,
            )
        )

        assertFalse(
            prompt.contains(
                "llama.cpp",
                ignoreCase = true,
            )
        )

        assertFalse(
            prompt.contains(
                "LiteRT",
                ignoreCase = true,
            )
        )
    }

    @Test
    fun build_placesOutputContractAfterConversationEvidence() {
        val prompt =
            ResponseEvaluationPromptBuilder.build(
                createRequest(
                    previousFollowUps =
                        listOf(
                            ResponseFollowUpExchange(
                                question =
                                    "What unit does 20 represent?",
                                answer =
                                    "percent",
                            )
                        ),
                )
            )

        val transcriptIndex =
            prompt.indexOf(
                "RESPONDENT EVIDENCE TRANSCRIPT"
            )

        val answerIndex =
            prompt.indexOf(
                "RESPONDENT:\npercent",
                startIndex =
                    transcriptIndex,
            )

        val instructionIndex =
            prompt.indexOf(
                "Do not evaluate the original answer in isolation.",
                startIndex =
                    answerIndex,
            )

        val outputContractIndex =
            prompt.indexOf(
                "OUTPUT CONTRACT"
            )

        assertTrue(
            transcriptIndex >= 0
        )

        assertTrue(
            answerIndex > transcriptIndex
        )

        assertTrue(
            instructionIndex > answerIndex
        )

        assertTrue(
            outputContractIndex > instructionIndex
        )
    }

    private fun createRequest(
        previousFollowUps: List<ResponseFollowUpExchange> =
            emptyList(),
    ): ResponseEvaluationRequest {
        return ResponseEvaluationRequest(
            surveyId =
                "agriculture-maize-v2",
            language =
                "en",
            interviewerInstruction =
                "Conduct the interview clearly and neutrally.",
            questionId =
                "Q1",
            question =
                "How much yield do you lose because of fall armyworm? " +
                        "Please think back over the last 3 seasons. " +
                        "Percent or bags per acre are fine.",
            interviewGoal =
                "Understand the magnitude, unit, and recent-season context.",
            originalAnswer =
                "20",
            previousFollowUps =
                previousFollowUps,
        )
    }
}
