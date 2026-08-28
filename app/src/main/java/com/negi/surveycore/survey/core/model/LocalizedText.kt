package com.negi.surveycore.survey.core.model

/**
 * Language-aware text used by survey definitions.
 *
 * The default text is always available. Optional translations may
 * override it for specific language codes.
 */
data class LocalizedText(
    val default: String,
    val translations: Map<String, String> = emptyMap(),
) {

    fun resolve(
        language: String,
    ): String {
        return translations[language]
            ?.takeIf { it.isNotBlank() }
            ?: default
    }
}