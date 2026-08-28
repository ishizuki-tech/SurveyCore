package com.negi.surveycore.ai.backend.llamacpp

import org.junit.Assert.assertEquals
import org.junit.Test

class QwenThinkingOutputProcessorTest {

    @Test
    fun removesLeadingThinkingBlock() {
        val raw =
            """
            <think>

            </think>

            VALID
            """.trimIndent()

        val result =
            QwenThinkingOutputProcessor.process(
                raw
            )

        assertEquals(
            "VALID",
            result,
        )
    }

    @Test
    fun preservesNormalOutput() {
        val result =
            QwenThinkingOutputProcessor.process(
                "CLARIFY: Please specify the unit."
            )

        assertEquals(
            "CLARIFY: Please specify the unit.",
            result,
        )
    }

    @Test
    fun removesNonEmptyThinkingContent() {
        val raw =
            """
            <think>
            The answer needs further clarification.
            </think>

            CLARIFY: Please specify the unit.
            """.trimIndent()

        val result =
            QwenThinkingOutputProcessor.process(
                raw
            )

        assertEquals(
            "CLARIFY: Please specify the unit.",
            result,
        )
    }
}