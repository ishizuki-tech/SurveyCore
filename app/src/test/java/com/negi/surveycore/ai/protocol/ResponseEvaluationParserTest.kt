package com.negi.surveycore.ai.protocol

import com.negi.surveycore.survey.core.ai.ResponseEvaluationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseEvaluationParserTest {

    @Test
    fun parse_done_returnsDone() {
        val result =
            ResponseEvaluationParser.parse(
                """
                REMAINING_GAP: NONE
                STATUS: DONE
                QUESTION: NONE
                SUFFICIENCY: 95
                """.trimIndent(),
            )

        assertEquals(
            ResponseEvaluationResult.Done(
                sufficiency = 95,
            ),
            result,
        )
    }

    @Test
    fun parse_doneAtMinimumDoneScore_returnsDone() {
        val result =
            ResponseEvaluationParser.parse(
                """
                REMAINING_GAP: NONE
                STATUS: DONE
                QUESTION: NONE
                SUFFICIENCY: 81
                """.trimIndent(),
            )

        assertEquals(
            ResponseEvaluationResult.Done(
                sufficiency = 81,
            ),
            result,
        )
    }

    @Test
    fun parse_followUp_returnsFollowUp() {
        val result =
            ResponseEvaluationParser.parse(
                """
                REMAINING_GAP: The measurement unit is unclear.
                STATUS: FOLLOW_UP
                QUESTION: What unit does 20 represent?
                SUFFICIENCY: 35
                """.trimIndent(),
            )

        assertEquals(
            ResponseEvaluationResult.FollowUp(
                sufficiency = 35,
                gap = "The measurement unit is unclear.",
                question = "What unit does 20 represent?",
            ),
            result,
        )
    }

    @Test
    fun parse_followUpAtMaximumFollowUpScore_returnsFollowUp() {
        val result =
            ResponseEvaluationParser.parse(
                """
                REMAINING_GAP: One important detail is still missing.
                STATUS: FOLLOW_UP
                QUESTION: Could you clarify that detail?
                SUFFICIENCY: 80
                """.trimIndent(),
            )

        assertEquals(
            ResponseEvaluationResult.FollowUp(
                sufficiency = 80,
                gap = "One important detail is still missing.",
                question = "Could you clarify that detail?",
            ),
            result,
        )
    }

    @Test
    fun parse_allowsBlankLinesAroundProtocol() {
        val result =
            ResponseEvaluationParser.parse(
                """

                REMAINING_GAP: NONE

                STATUS: DONE
                QUESTION: NONE

                SUFFICIENCY: 100

                """.trimIndent(),
            )

        assertEquals(
            ResponseEvaluationResult.Done(
                sufficiency = 100,
            ),
            result,
        )
    }

    @Test
    fun parse_sufficiencyZero_isAcceptedForFollowUp() {
        val result =
            ResponseEvaluationParser.parse(
                """
                REMAINING_GAP: The answer does not address the interview goal.
                STATUS: FOLLOW_UP
                QUESTION: Could you answer the survey question?
                SUFFICIENCY: 0
                """.trimIndent(),
            )

        assertEquals(
            ResponseEvaluationResult.FollowUp(
                sufficiency = 0,
                gap = "The answer does not address the interview goal.",
                question = "Could you answer the survey question?",
            ),
            result,
        )
    }

    @Test
    fun parse_doneWithScore80_returnsFailure() {
        val result =
            ResponseEvaluationParser.parse(
                """
                REMAINING_GAP: NONE
                STATUS: DONE
                QUESTION: NONE
                SUFFICIENCY: 80
                """.trimIndent(),
            )

        assertFailed(
            result = result,
            expectedReason =
                "STATUS: DONE requires SUFFICIENCY from 81 through 100.",
        )
    }

    @Test
    fun parse_followUpWithScore81_returnsFailure() {
        val result =
            ResponseEvaluationParser.parse(
                """
                REMAINING_GAP: The measurement unit is unclear.
                STATUS: FOLLOW_UP
                QUESTION: What unit does 20 represent?
                SUFFICIENCY: 81
                """.trimIndent(),
            )

        assertFailed(
            result = result,
            expectedReason =
                "STATUS: FOLLOW_UP requires SUFFICIENCY from 0 through 80.",
        )
    }

    @Test
    fun parse_sufficiencyAbove100_returnsFailure() {
        val result =
            ResponseEvaluationParser.parse(
                """
                REMAINING_GAP: NONE
                STATUS: DONE
                QUESTION: NONE
                SUFFICIENCY: 101
                """.trimIndent(),
            )

        assertFailed(result)
    }

    @Test
    fun parse_negativeSufficiency_returnsFailure() {
        val result =
            ResponseEvaluationParser.parse(
                """
                REMAINING_GAP: Missing information.
                STATUS: FOLLOW_UP
                QUESTION: Please provide more information.
                SUFFICIENCY: -1
                """.trimIndent(),
            )

        assertFailed(result)
    }

    @Test
    fun parse_nonIntegerSufficiency_returnsFailure() {
        val result =
            ResponseEvaluationParser.parse(
                """
                REMAINING_GAP: Missing information.
                STATUS: FOLLOW_UP
                QUESTION: Please provide more information.
                SUFFICIENCY: 75.5
                """.trimIndent(),
            )

        assertFailed(result)
    }

    @Test
    fun parse_rangeSufficiency_returnsFailure() {
        val result =
            ResponseEvaluationParser.parse(
                """
                REMAINING_GAP: NONE
                STATUS: DONE
                QUESTION: NONE
                SUFFICIENCY: 81-99
                """.trimIndent(),
            )

        assertFailed(result)
    }

    @Test
    fun parse_unknownStatus_returnsFailure() {
        val result =
            ResponseEvaluationParser.parse(
                """
                REMAINING_GAP: NONE
                STATUS: VALID
                QUESTION: NONE
                SUFFICIENCY: 50
                """.trimIndent(),
            )

        assertFailed(result)
    }

    @Test
    fun parse_doneWithRemainingGap_returnsFailure() {
        val result =
            ResponseEvaluationParser.parse(
                """
                REMAINING_GAP: The unit is unclear.
                STATUS: DONE
                QUESTION: NONE
                SUFFICIENCY: 90
                """.trimIndent(),
            )

        assertFailed(
            result = result,
            expectedReason =
                "STATUS: DONE requires REMAINING_GAP: NONE.",
        )
    }

    @Test
    fun parse_doneWithQuestion_returnsFailure() {
        val result =
            ResponseEvaluationParser.parse(
                """
                REMAINING_GAP: NONE
                STATUS: DONE
                QUESTION: What unit did you mean?
                SUFFICIENCY: 90
                """.trimIndent(),
            )

        assertFailed(result)
    }

    @Test
    fun parse_followUpWithNoneRemainingGap_returnsFailure() {
        val result =
            ResponseEvaluationParser.parse(
                """
                REMAINING_GAP: NONE
                STATUS: FOLLOW_UP
                QUESTION: What unit did you mean?
                SUFFICIENCY: 50
                """.trimIndent(),
            )

        assertFailed(
            result = result,
            expectedReason =
                "STATUS: FOLLOW_UP requires a non-empty REMAINING_GAP.",
        )
    }

    @Test
    fun parse_followUpWithNoneQuestion_returnsFailure() {
        val result =
            ResponseEvaluationParser.parse(
                """
                REMAINING_GAP: The measurement unit is unclear.
                STATUS: FOLLOW_UP
                QUESTION: NONE
                SUFFICIENCY: 50
                """.trimIndent(),
            )

        assertFailed(result)
    }

    @Test
    fun parse_missingProtocolLine_returnsFailure() {
        val result =
            ResponseEvaluationParser.parse(
                """
                REMAINING_GAP: The measurement unit is unclear.
                STATUS: FOLLOW_UP
                QUESTION: What unit did you mean?
                """.trimIndent(),
            )

        assertFailed(result)
    }

    @Test
    fun parse_extraExplanation_returnsFailure() {
        val result =
            ResponseEvaluationParser.parse(
                """
                REMAINING_GAP: NONE
                STATUS: DONE
                QUESTION: NONE
                SUFFICIENCY: 95
                The response is sufficiently complete.
                """.trimIndent(),
            )

        assertFailed(result)
    }

    @Test
    fun parse_legacyGapLabel_returnsFailure() {
        val result =
            ResponseEvaluationParser.parse(
                """
                GAP: NONE
                STATUS: DONE
                QUESTION: NONE
                SUFFICIENCY: 95
                """.trimIndent(),
            )

        assertFailed(
            result = result,
            expectedReason =
                "Expected first line to start with 'REMAINING_GAP:'.",
        )
    }

    @Test
    fun parse_oldStatusFirstOrder_returnsFailure() {
        val result =
            ResponseEvaluationParser.parse(
                """
                STATUS: DONE
                REMAINING_GAP: NONE
                QUESTION: NONE
                SUFFICIENCY: 95
                """.trimIndent(),
            )

        assertFailed(
            result = result,
            expectedReason =
                "Expected first line to start with 'REMAINING_GAP:'.",
        )
    }

    @Test
    fun parse_wrongLineOrder_returnsFailure() {
        val result =
            ResponseEvaluationParser.parse(
                """
                REMAINING_GAP: NONE
                QUESTION: NONE
                STATUS: DONE
                SUFFICIENCY: 95
                """.trimIndent(),
            )

        assertFailed(result)
    }

    @Test
    fun parse_emptyText_returnsFailure() {
        val result =
            ResponseEvaluationParser.parse(
                ""
            )

        assertFailed(result)
    }

    private fun assertFailed(
        result: ResponseEvaluationResult,
        expectedReason: String? = null,
    ) {
        assertTrue(
            "Expected Failed but received $result",
            result is ResponseEvaluationResult.Failed,
        )

        if (expectedReason != null) {
            assertEquals(
                expectedReason,
                (result as ResponseEvaluationResult.Failed).reason,
            )
        }
    }
}
