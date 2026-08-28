package com.negi.surveycore.survey.core.model

/**
 * Root definition for a survey.
 *
 * This model is runtime-agnostic. It does not depend on Android,
 * LiteRT-LM, llama.cpp, or any specific user interface.
 */
data class SurveyDefinition(
    val schemaVersion: Int,
    val metadata: SurveyMetadata,
    val interviewer: InterviewerDefinition,
    val flow: SurveyFlowDefinition,
)

data class SurveyMetadata(
    val id: String,
    val version: Int,
    val title: LocalizedText,
    val defaultLanguage: String,
    val supportedLanguages: List<String> = listOf(defaultLanguage),
)

data class InterviewerDefinition(
    val instruction: LocalizedText,
)