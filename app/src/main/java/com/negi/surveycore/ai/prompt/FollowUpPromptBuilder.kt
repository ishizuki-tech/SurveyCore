package com.negi.surveycore.ai.prompt

import com.negi.surveycore.survey.core.ai.FollowUpEvaluationRequest

/**
 * Builds the prompt used to evaluate one specific research follow-up target.
 *
 * SurveyEngine selects the target.
 * The model must not choose another target, advance the survey,
 * or decide which major question comes next.
 */
object FollowUpPromptBuilder {

    val SYSTEM_INSTRUCTION: String =
        """
        You are a survey follow-up evaluator.

        Evaluate only the current research target.

        Do not choose another target.
        Do not introduce a new survey topic.
        Do not decide which major question comes next.
        Do not invent facts about the respondent.
        Do not rewrite the respondent's answers.

        Your output must use exactly one of these formats:

        SATISFIED

        FOLLOW_UP: <one short follow-up question>

        Return SATISFIED only when the available respondent evidence
        sufficiently addresses the current target.

        Otherwise ask exactly one concise question that collects the
        most important missing information for the current target.
        """.trimIndent()

    fun build(
        request: FollowUpEvaluationRequest,
    ): String {
        return buildString {
            appendLine("SURVEY:")
            appendLine(request.surveyId)
            appendLine()

            appendLine("LANGUAGE:")
            appendLine(request.language)
            appendLine()

            appendLine("INTERVIEWER INSTRUCTION:")
            appendLine(request.interviewerInstruction)
            appendLine()

            appendLine("QUESTION ID:")
            appendLine(request.questionId)
            appendLine()

            appendLine("MAJOR QUESTION:")
            appendLine(request.majorQuestion)
            appendLine()

            appendLine("ACCEPTED MAJOR ANSWER:")
            appendLine(request.majorAnswer)
            appendLine()

            appendLine("CURRENT TARGET ID:")
            appendLine(request.targetId)
            appendLine()

            appendLine("CURRENT TARGET:")
            appendLine(request.targetDescription)
            appendLine()

            if (request.previousFollowUps.isNotEmpty()) {
                appendLine("FOLLOW-UP HISTORY:")

                request.previousFollowUps
                    .filter {
                        it.targetId == request.targetId
                    }
                    .forEachIndexed { index, exchange ->
                        appendLine(
                            "${index + 1}. Question: ${exchange.question}"
                        )

                        appendLine(
                            "   Answer: ${exchange.answer}"
                        )
                    }

                appendLine()
            }

            appendLine(
                "Evaluate the accepted major answer together with the " +
                        "follow-up evidence for the current target."
            )

            appendLine(
                "Return only SATISFIED or FOLLOW_UP: followed by one question."
            )
        }
    }
}