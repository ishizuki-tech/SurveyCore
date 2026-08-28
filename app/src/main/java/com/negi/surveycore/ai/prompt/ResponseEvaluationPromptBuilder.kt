package com.negi.surveycore.ai.prompt

import com.negi.surveycore.survey.core.ai.ResponseEvaluationRequest

/**
 * Builds the prompt used for one integrated evaluation of a respondent turn.
 *
 * The model receives the complete accumulated respondent evidence for the
 * current major survey question and makes one interviewer decision:
 *
 * - DONE when no important information required by the interview goal remains.
 * - FOLLOW_UP when one important required information gap remains.
 *
 * Survey progression, retry behavior, follow-up limits, and storage remain
 * deterministic responsibilities of Survey Core.
 */
object ResponseEvaluationPromptBuilder {

    fun build(request: ResponseEvaluationRequest): String {
        return buildString {
            appendLine("You are evaluating one respondent turn in a structured survey interview.")
            appendLine()
            appendLine("Decide whether the accumulated respondent answer is sufficient for the INTERVIEW GOAL.")
            appendLine("Identify the most important required REMAINING_GAP first.")
            appendLine("Then decide STATUS from that remaining gap, produce QUESTION if needed, and assign SUFFICIENCY last.")
            appendLine("All four fields must agree with the same evaluation.")
            appendLine()
            appendLine("How to interpret the evidence:")
            appendLine("- The SURVEY QUESTION establishes the topic and context of the respondent's answer.")
            appendLine("- Context stated by the question, such as a requested time period or subject, may")
            appendLine("  give meaning to a concise respondent answer without requiring the respondent")
            appendLine("  to repeat that context.")
            appendLine("- Examples, suggested units, answer formats, or alternatives shown in the survey")
            appendLine("  question are not respondent-selected facts.")
            appendLine("- When the question offers multiple possible units or formats, a bare number does")
            appendLine("  not identify which one the respondent means.")
            appendLine("- The ORIGINAL RESPONDENT ANSWER and all FOLLOW-UP HISTORY together form the")
            appendLine("  accumulated respondent evidence.")
            appendLine("- A short follow-up answer may be interpreted together with the follow-up question")
            appendLine("  that immediately preceded it.")
            appendLine("- Do not invent facts.")
            appendLine("- Do not ask the respondent to repeat information whose meaning is already clear")
            appendLine("  from the respondent evidence interpreted in the survey-question context.")
            appendLine("- A follow-up QUESTION must ask only for the remaining missing information without pretending that")
            appendLine("  information is already known.")
            appendLine("- Preserve ambiguous respondent evidence as given. Do not attach a missing unit, label,")
            appendLine("  category, qualifier, or alternative unless the respondent actually supplied it.")
            appendLine("- If the respondent explicitly supplies a value together with a unit, label, category,")
            appendLine("  qualifier, or alternative, treat that information as present evidence and do not")
            appendLine("  declare that same information missing.")
            appendLine("- When referring to ambiguous evidence in a follow-up question, quote or reuse the")
            appendLine("  respondent's wording without adding the missing information.")
            appendLine("- Do not require optional detail beyond the INTERVIEW GOAL.")

            appendLine()
            appendLine("REMAINING-GAP-FIRST DECISION:")
            appendLine("- First compare the accumulated respondent evidence with each required part of the INTERVIEW GOAL.")
            appendLine("- If any required part is still missing or ambiguous, REMAINING_GAP must not be NONE.")
            appendLine("- If several required gaps remain, put only the single most important one in REMAINING_GAP.")
            appendLine("- Use REMAINING_GAP: NONE only after confirming that no important required gap remains.")
            appendLine("- Choose FOLLOW_UP when REMAINING_GAP is real, and ask exactly one concise question that addresses it.")
            appendLine("- Choose DONE only when REMAINING_GAP is NONE.")
            appendLine()
            appendLine("SUFFICIENCY:")
            appendLine("- Assign SUFFICIENCY only after identifying REMAINING_GAP, deciding STATUS, and producing QUESTION.")
            appendLine("- Output one integer from 0 through 100.")
            appendLine("- Output digits for one number only. Never output a range or explanation.")
            appendLine("- FOLLOW_UP requires a score of 80 or lower.")
            appendLine("- DONE requires a score of 81 or higher.")
            appendLine()
            appendLine("SURVEY QUESTION")
            appendLine(request.question.trim())
            appendLine()
            appendLine("INTERVIEW GOAL")
            appendLine(request.interviewGoal.trim())
            appendLine()
            if (request.previousFollowUps.isEmpty()) {
                appendLine("ORIGINAL RESPONDENT ANSWER")
                appendLine(request.originalAnswer.trim())
                appendLine()
                appendFollowUpHistory(request)
            } else {
                appendEvidenceTranscript(request)
            }

            appendLine()
            appendLine("OUTPUT CONTRACT")
            appendLine("Return exactly four lines and nothing else, in this exact order:")
            appendLine("REMAINING_GAP: <short description or NONE>")
            appendLine("STATUS: DONE|FOLLOW_UP")
            appendLine("QUESTION: <one follow-up question or NONE>")
            appendLine("SUFFICIENCY: <one integer>")
            appendLine()
            appendLine("Final consistency check:")

            if (request.previousFollowUps.isNotEmpty()) {
                appendLine(
                    "- REMAINING_GAP describes only information that still remains missing or ambiguous after " +
                            "considering the complete respondent evidence transcript."
                )
                appendLine(
                    "- Never put a resolved, historical, or already answered gap in REMAINING_GAP."
                )
                appendLine(
                    "- If STATUS is DONE, REMAINING_GAP must be exactly NONE and QUESTION must be exactly NONE."
                )
            }

            appendLine("- Identify REMAINING_GAP before generating any other output field.")
            appendLine("- REMAINING_GAP: NONE requires STATUS: DONE and QUESTION: NONE.")
            appendLine("- A real REMAINING_GAP requires STATUS: FOLLOW_UP and one real QUESTION.")
            appendLine("- FOLLOW_UP requires SUFFICIENCY from 0 through 80.")
            appendLine("- DONE requires SUFFICIENCY from 81 through 100.")
            appendLine("- Never output DONE with a real REMAINING_GAP or QUESTION.")
            appendLine("- Never output FOLLOW_UP with REMAINING_GAP: NONE or QUESTION: NONE.")
            appendLine("- Do not include markdown, reasoning, extra labels, or any extra text.")
        }.trim()
    }

    /**
     * Serializes accumulated multi-turn evidence as a natural conversation.
     *
     * The evaluator receives the original respondent answer followed by each
     * interviewer follow-up and respondent answer in order. Survey Core does
     * not merge or reinterpret answer semantics; it only preserves the actual
     * conversation so the model can evaluate the complete evidence.
     */
    private fun StringBuilder.appendEvidenceTranscript(
        request: ResponseEvaluationRequest,
    ) {
        appendLine("RESPONDENT EVIDENCE TRANSCRIPT")
        appendLine()

        appendLine("RESPONDENT:")
        appendLine(request.originalAnswer.trim())

        request.previousFollowUps.forEach { exchange ->
            appendLine()
            appendLine("INTERVIEWER:")
            appendLine(exchange.question.trim())
            appendLine()
            appendLine("RESPONDENT:")
            appendLine(exchange.answer.trim())
        }

        appendLine()
        appendLine("Evaluate the complete transcript above as one accumulated response.")
        appendLine("Do not evaluate the original answer in isolation.")
    }

    private fun StringBuilder.appendFollowUpHistory(
        request: ResponseEvaluationRequest,
    ) {
        appendLine("FOLLOW-UP HISTORY")

        if (request.previousFollowUps.isEmpty()) {
            appendLine("NONE")
            return
        }

        request.previousFollowUps.forEachIndexed { index, exchange ->
            val number =
                index + 1

            appendLine("FOLLOW-UP $number QUESTION")
            appendLine(exchange.question.trim())
            appendLine()

            appendLine("FOLLOW-UP $number RESPONDENT ANSWER")
            appendLine(exchange.answer.trim())

            if (index != request.previousFollowUps.lastIndex) {
                appendLine()
            }
        }
    }
}
