package com.negi.surveycore.survey.source

import com.negi.surveycore.survey.core.model.SurveyDefinition

/**
 * Source abstraction for loading survey definitions.
 *
 * Survey consumers do not need to know whether a survey comes from
 * Android assets, a local file, a database, or another source.
 */
fun interface SurveySource {

    fun load(): SurveyDefinition
}