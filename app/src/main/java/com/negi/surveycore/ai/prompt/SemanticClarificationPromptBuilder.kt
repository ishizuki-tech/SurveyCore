package com.negi.surveycore.ai.prompt

import com.negi.surveycore.survey.core.ai.SemanticValidationRequest

/**
 * Builds a prompt for generating one clarification question for a criterion
 * that ModelSurveyAi has already determined is unsatisfied.
 *
 * This builder does not ask the model to select a criterion or decide whether
 * the overall survey answer is valid.
 */
object SemanticClarificationPromptBuilder {

    val SYSTEM_INSTRUCTION: String =
        """
        You generate one short survey clarification question.

        The missing validation criterion has already been selected for you.

        Do not decide whether the overall answer is valid.
        Do not select a different criterion.
        Ask only about the supplied missing criterion.

        Use the accumulated respondent record when wording the question.

        Do not invent, assume, suggest, or choose a missing respondent value,
        unit, category, time period, frequency, duration, reason, or other
        answer.

        Facts explicitly supplied by the respondent may be referenced.

        A clarification question from an earlier exchange may be used only to
        understand its paired respondent answer.

        Write the question in the requested response language.

        Output exactly:

        CLARIFY: <one short clarification question>

        Do not output reasoning, markdown, or explanations.
        """.trimIndent()

    fun build(
        request: SemanticValidationRequest,
        missingCriterion: String,
    ): String {
        return buildString {
            appendLine(
                "RESPONDENT RECORD"
            )
            appendLine()

            appendLine(
                "Original answer:"
            )
            appendLine(
                request.originalAnswer
            )

            appendLine()

            if (
                request.previousClarifications.isEmpty()
            ) {
                appendLine(
                    "Clarification exchanges: none"
                )
            } else {
                appendLine(
                    "Clarification exchanges:"
                )

                request.previousClarifications
                    .forEachIndexed {
                            index,
                            exchange,
                        ->

                        appendLine()
                        appendLine(
                            "Exchange ${index + 1}:"
                        )

                        appendLine(
                            "Question:"
                        )
                        appendLine(
                            exchange.question
                        )

                        appendLine(
                            "Respondent answer:"
                        )
                        appendLine(
                            exchange.answer
                        )
                    }
            }

            appendLine()
            appendLine(
                "MISSING CRITERION"
            )
            appendLine(
                missingCriterion
            )

            appendLine()
            appendLine(
                "SURVEY QUESTION CONTEXT"
            )
            appendLine(
                request.question
            )

            appendLine()
            appendLine(
                "INTERVIEWER INSTRUCTION"
            )
            appendLine(
                request.interviewerInstruction
            )

            appendLine()
            appendLine(
                "RESPONSE LANGUAGE"
            )
            appendLine(
                request.language
            )

            appendLine()
            appendLine(
                "Generate one concise clarification question for the missing " +
                        "criterion only."
            )

            appendLine(
                "Do not supply the missing answer inside the question."
            )

            appendLine()
            appendLine(
                "Return exactly:"
            )
            appendLine(
                "CLARIFY: <one short clarification question>"
            )
        }
    }
}