package com.negi.surveycore.survey.source

import android.content.Context
import com.negi.surveycore.survey.core.model.SurveyDefinition
import com.negi.surveycore.survey.yaml.SurveyValidator
import com.negi.surveycore.survey.yaml.SurveyYamlLoader

/**
 * Loads and validates one survey definition from Android assets.
 *
 * Invalid surveys are rejected before they can reach SurveyEngine.
 */
class AssetSurveySource(
    context: Context,
    private val assetPath: String,
) : SurveySource {

    private val applicationContext =
        context.applicationContext

    override fun load(): SurveyDefinition {
        val inputStream =
            applicationContext.assets.open(
                assetPath
            )

        val definition =
            SurveyYamlLoader.load(
                inputStream
            )

        return SurveyValidator.requireValid(
            definition
        )
    }
}