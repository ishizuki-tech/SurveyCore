package com.negi.surveycore.survey.yaml

import com.negi.surveycore.survey.core.model.AnswerDefinition
import com.negi.surveycore.survey.core.model.AnswerOption
import com.negi.surveycore.survey.core.model.AnswerType
import com.negi.surveycore.survey.core.model.DeterministicValidationDefinition
import com.negi.surveycore.survey.core.model.EndNode
import com.negi.surveycore.survey.core.model.FollowUpDefinition
import com.negi.surveycore.survey.core.model.FollowUpTargetDefinition
import com.negi.surveycore.survey.core.model.InputMode
import com.negi.surveycore.survey.core.model.InterviewerDefinition
import com.negi.surveycore.survey.core.model.LocalizedText
import com.negi.surveycore.survey.core.model.NavigationDefinition
import com.negi.surveycore.survey.core.model.QuestionDefinition
import com.negi.surveycore.survey.core.model.QuestionNode
import com.negi.surveycore.survey.core.model.ReviewNode
import com.negi.surveycore.survey.core.model.SemanticValidationDefinition
import com.negi.surveycore.survey.core.model.StartNode
import com.negi.surveycore.survey.core.model.SurveyDefinition
import com.negi.surveycore.survey.core.model.SurveyFlowDefinition
import com.negi.surveycore.survey.core.model.SurveyMetadata
import com.negi.surveycore.survey.core.model.SurveyNode
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.InputStream

/**
 * Loads SurveyDefinition instances from YAML.
 *
 * This loader is intentionally independent of Android Context and asset
 * APIs. Callers provide an InputStream from any source.
 *
 * Semantic validation and follow-up behavior are represented as data only.
 * No AI implementation is invoked while parsing YAML.
 */
object SurveyYamlLoader {

    fun load(
        inputStream: InputStream,
    ): SurveyDefinition {
        inputStream.use { stream ->
            val loaderOptions =
                LoaderOptions()

            val yaml =
                Yaml(
                    SafeConstructor(
                        loaderOptions
                    )
                )

            val rootValue =
                yaml.load<Any?>(stream)

            val root =
                rootValue.asStringMap(
                    location = "root"
                )

            return parseSurveyDefinition(root)
        }
    }

    private fun parseSurveyDefinition(
        root: Map<String, Any?>,
    ): SurveyDefinition {
        val schemaVersion =
            root.requiredInt(
                key = "schemaVersion",
                location = "root",
            )

        val surveyMap =
            root.requiredMap(
                key = "survey",
                location = "root",
            )

        val interviewerMap =
            root.requiredMap(
                key = "interviewer",
                location = "root",
            )

        val flowMap =
            root.requiredMap(
                key = "flow",
                location = "root",
            )

        return SurveyDefinition(
            schemaVersion = schemaVersion,

            metadata =
                parseMetadata(
                    surveyMap
                ),

            interviewer =
                parseInterviewer(
                    interviewerMap
                ),

            flow =
                parseFlow(
                    flowMap
                ),
        )
    }

    private fun parseMetadata(
        map: Map<String, Any?>,
    ): SurveyMetadata {
        val defaultLanguage =
            map.requiredString(
                key = "defaultLanguage",
                location = "survey",
            )

        val supportedLanguages =
            map.optionalStringList(
                key = "supportedLanguages",
                location = "survey",
            ).ifEmpty {
                listOf(defaultLanguage)
            }

        return SurveyMetadata(
            id =
                map.requiredString(
                    key = "id",
                    location = "survey",
                ),

            version =
                map.requiredInt(
                    key = "version",
                    location = "survey",
                ),

            title =
                map.requiredLocalizedText(
                    key = "title",
                    location = "survey",
                ),

            defaultLanguage =
                defaultLanguage,

            supportedLanguages =
                supportedLanguages,
        )
    }

    private fun parseInterviewer(
        map: Map<String, Any?>,
    ): InterviewerDefinition {
        return InterviewerDefinition(
            instruction =
                map.requiredLocalizedText(
                    key = "instruction",
                    location = "interviewer",
                )
        )
    }

    private fun parseFlow(
        map: Map<String, Any?>,
    ): SurveyFlowDefinition {
        val nodeValues =
            map.requiredList(
                key = "nodes",
                location = "flow",
            )

        val nodes =
            nodeValues.mapIndexed {
                    index,
                    value,
                ->

                val nodeMap =
                    value.asStringMap(
                        location =
                            "flow.nodes[$index]"
                    )

                parseNode(
                    map = nodeMap,
                    location =
                        "flow.nodes[$index]",
                )
            }

        return SurveyFlowDefinition(
            startNodeId =
                map.requiredString(
                    key = "startNodeId",
                    location = "flow",
                ),

            nodes =
                nodes,
        )
    }

    private fun parseNode(
        map: Map<String, Any?>,
        location: String,
    ): SurveyNode {
        val id =
            map.requiredString(
                key = "id",
                location = location,
            )

        val type =
            map.requiredString(
                key = "type",
                location = location,
            ).lowercase()

        val title =
            map.optionalLocalizedText(
                key = "title",
                location = location,
            )

        return when (type) {
            "start" ->
                StartNode(
                    id = id,
                    title = title,
                    nextNodeId =
                        map.requiredString(
                            key = "next",
                            location = location,
                        ),
                )

            "question" ->
                parseQuestionNode(
                    map = map,
                    id = id,
                    title = title,
                    location = location,
                )

            "review" ->
                ReviewNode(
                    id = id,
                    title = title,
                    navigation =
                        NavigationDefinition(
                            defaultNextNodeId =
                                map.optionalString(
                                    key = "next",
                                    location = location,
                                ),
                        ),
                )

            "end" ->
                EndNode(
                    id = id,
                    title = title,
                    completionMessage =
                        map.optionalLocalizedText(
                            key = "completionMessage",
                            location = location,
                        ),
                )

            else ->
                throw SurveyYamlException(
                    "$location.type has unsupported node type '$type'."
                )
        }
    }

    private fun parseQuestionNode(
        map: Map<String, Any?>,
        id: String,
        title: LocalizedText?,
        location: String,
    ): QuestionNode {
        val answerMap =
            map.requiredMap(
                key = "answer",
                location = location,
            )

        val followUpMap =
            map.optionalMap(
                key = "followUp",
                location = location,
            )

        return QuestionNode(
            id = id,
            title = title,

            question =
                QuestionDefinition(
                    prompt =
                        map.requiredLocalizedText(
                            key = "prompt",
                            location = location,
                        ),

                    answer =
                        parseAnswerDefinition(
                            map = answerMap,
                            location =
                                "$location.answer",
                        ),

                    followUp =
                        if (followUpMap != null) {
                            parseFollowUpDefinition(
                                map = followUpMap,
                                location =
                                    "$location.followUp",
                            )
                        } else {
                            FollowUpDefinition.disabled()
                        },
                ),

            navigation =
                NavigationDefinition(
                    defaultNextNodeId =
                        map.optionalString(
                            key = "next",
                            location = location,
                        ),
                ),
        )
    }

    private fun parseAnswerDefinition(
        map: Map<String, Any?>,
        location: String,
    ): AnswerDefinition {
        val answerType =
            parseAnswerType(
                value =
                    map.requiredString(
                        key = "type",
                        location = location,
                    ),
                location =
                    "$location.type",
            )

        val inputModes =
            map.optionalStringList(
                key = "inputModes",
                location = location,
            )
                .ifEmpty {
                    listOf("text")
                }
                .map {
                    parseInputMode(
                        value = it,
                        location =
                            "$location.inputModes",
                    )
                }
                .toSet()

        val deterministicValidationMap =
            map.optionalMap(
                key = "deterministicValidation",
                location = location,
            )

        val semanticValidationMap =
            map.optionalMap(
                key = "semanticValidation",
                location = location,
            )

        val optionValues =
            map.optionalList(
                key = "options",
                location = location,
            )

        return AnswerDefinition(
            type =
                answerType,

            required =
                map.optionalBoolean(
                    key = "required",
                    location = location,
                ) ?: true,

            inputModes =
                inputModes,

            options =
                optionValues.mapIndexed {
                        index,
                        value,
                    ->

                    parseAnswerOption(
                        value =
                            value,
                        location =
                            "$location.options[$index]",
                    )
                },

            deterministicValidation =
                if (
                    deterministicValidationMap != null
                ) {
                    parseDeterministicValidation(
                        map =
                            deterministicValidationMap,
                        location =
                            "$location.deterministicValidation",
                    )
                } else {
                    DeterministicValidationDefinition()
                },

            semanticValidation =
                if (
                    semanticValidationMap != null
                ) {
                    parseSemanticValidation(
                        map =
                            semanticValidationMap,
                        location =
                            "$location.semanticValidation",
                    )
                } else {
                    SemanticValidationDefinition.disabled()
                },
        )
    }

    private fun parseAnswerOption(
        value: Any?,
        location: String,
    ): AnswerOption {
        val map =
            value.asStringMap(
                location = location
            )

        return AnswerOption(
            id =
                map.requiredString(
                    key = "id",
                    location = location,
                ),

            label =
                map.requiredLocalizedText(
                    key = "label",
                    location = location,
                ),
        )
    }

    private fun parseDeterministicValidation(
        map: Map<String, Any?>,
        location: String,
    ): DeterministicValidationDefinition {
        return DeterministicValidationDefinition(
            minNumber =
                map.optionalDouble(
                    key = "minNumber",
                    location = location,
                ),

            maxNumber =
                map.optionalDouble(
                    key = "maxNumber",
                    location = location,
                ),

            minLength =
                map.optionalInt(
                    key = "minLength",
                    location = location,
                ),

            maxLength =
                map.optionalInt(
                    key = "maxLength",
                    location = location,
                ),
        )
    }

    private fun parseSemanticValidation(
        map: Map<String, Any?>,
        location: String,
    ): SemanticValidationDefinition {
        val enabled =
            map.optionalBoolean(
                key = "enabled",
                location = location,
            ) ?: false

        if (!enabled) {
            return SemanticValidationDefinition.disabled()
        }

        return SemanticValidationDefinition(
            enabled = true,

            goal =
                map.requiredString(
                    key = "goal",
                    location = location,
                ),

            criteria =
                map.optionalStringList(
                    key = "criteria",
                    location = location,
                ),

            maxClarifications =
                map.optionalInt(
                    key = "maxClarifications",
                    location = location,
                ) ?: 0,
        )
    }

    private fun parseFollowUpDefinition(
        map: Map<String, Any?>,
        location: String,
    ): FollowUpDefinition {
        val enabled =
            map.optionalBoolean(
                key = "enabled",
                location = location,
            ) ?: false

        if (!enabled) {
            return FollowUpDefinition.disabled()
        }

        /*
         * Unified response evaluation uses one semantic interview goal for
         * the complete respondent-turn evaluation.
         *
         * A blank goal remains allowed temporarily so legacy target-based
         * follow-up definitions can continue to load during migration.
         */
        val goal =
            map.optionalString(
                key = "goal",
                location = location,
            )
                ?.trim()
                .orEmpty()

        val targetValues =
            map.optionalList(
                key = "targets",
                location = location,
            )

        val targets =
            targetValues.mapIndexed {
                    index,
                    value,
                ->

                val targetLocation =
                    "$location.targets[$index]"

                val targetMap =
                    value.asStringMap(
                        location =
                            targetLocation
                    )

                FollowUpTargetDefinition(
                    id =
                        targetMap.requiredString(
                            key = "id",
                            location =
                                targetLocation,
                        ),

                    description =
                        targetMap.requiredString(
                            key = "description",
                            location =
                                targetLocation,
                        ),

                    maxAttempts =
                        targetMap.optionalInt(
                            key = "maxAttempts",
                            location =
                                targetLocation,
                        ) ?: 1,
                )
            }

        return FollowUpDefinition(
            enabled = true,

            maxQuestions =
                map.optionalInt(
                    key = "maxQuestions",
                    location = location,
                ) ?: 0,

            targets =
                targets,

            goal =
                goal,
        )
    }

    private fun parseAnswerType(
        value: String,
        location: String,
    ): AnswerType {
        return when (
            value
                .trim()
                .lowercase()
        ) {
            "text" ->
                AnswerType.TEXT

            "integer" ->
                AnswerType.INTEGER

            "decimal" ->
                AnswerType.DECIMAL

            "boolean" ->
                AnswerType.BOOLEAN

            "single_choice",
            "single-choice",
            "singlechoice",
                ->
                AnswerType.SINGLE_CHOICE

            "multi_choice",
            "multi-choice",
            "multichoice",
                ->
                AnswerType.MULTI_CHOICE

            else ->
                throw SurveyYamlException(
                    "$location has unsupported answer type '$value'."
                )
        }
    }

    private fun parseInputMode(
        value: String,
        location: String,
    ): InputMode {
        return when (
            value
                .trim()
                .lowercase()
        ) {
            "text" ->
                InputMode.TEXT

            "voice" ->
                InputMode.VOICE

            else ->
                throw SurveyYamlException(
                    "$location has unsupported input mode '$value'."
                )
        }
    }
}

/**
 * Indicates that a survey YAML document cannot be converted into a
 * SurveyDefinition.
 */
class SurveyYamlException(
    message: String,
) : IllegalArgumentException(message)

/*
 * YAML parsing helpers.
 *
 * SnakeYAML intentionally returns generic Map<*, *> and List<*> values.
 * These helpers centralize type checking and produce useful location-aware
 * error messages instead of unchecked casts scattered through the loader.
 */

private fun Any?.asStringMap(
    location: String,
): Map<String, Any?> {
    val source =
        this as? Map<*, *>
            ?: throw SurveyYamlException(
                "$location must be a mapping."
            )

    return source.entries.associate {
            entry ->

        val key =
            entry.key as? String
                ?: throw SurveyYamlException(
                    "$location contains a non-string key."
                )

        key to entry.value
    }
}

private fun Map<String, Any?>.requiredMap(
    key: String,
    location: String,
): Map<String, Any?> {
    val value =
        this[key]
            ?: throw SurveyYamlException(
                "$location.$key is required."
            )

    return value.asStringMap(
        location = "$location.$key"
    )
}

private fun Map<String, Any?>.optionalMap(
    key: String,
    location: String,
): Map<String, Any?>? {
    val value =
        this[key]
            ?: return null

    return value.asStringMap(
        location = "$location.$key"
    )
}

private fun Map<String, Any?>.requiredList(
    key: String,
    location: String,
): List<Any?> {
    return optionalList(
        key = key,
        location = location,
    ).takeIf {
        it.isNotEmpty()
    } ?: throw SurveyYamlException(
        "$location.$key is required and must not be empty."
    )
}

private fun Map<String, Any?>.optionalList(
    key: String,
    location: String,
): List<Any?> {
    val value =
        this[key]
            ?: return emptyList()

    return value as? List<Any?>
        ?: throw SurveyYamlException(
            "$location.$key must be a list."
        )
}

private fun Map<String, Any?>.requiredString(
    key: String,
    location: String,
): String {
    val value =
        this[key]
            ?: throw SurveyYamlException(
                "$location.$key is required."
            )

    val stringValue =
        value as? String
            ?: throw SurveyYamlException(
                "$location.$key must be a string."
            )

    if (stringValue.isBlank()) {
        throw SurveyYamlException(
            "$location.$key must not be blank."
        )
    }

    return stringValue
}

private fun Map<String, Any?>.optionalString(
    key: String,
    location: String,
): String? {
    val value =
        this[key]
            ?: return null

    return value as? String
        ?: throw SurveyYamlException(
            "$location.$key must be a string."
        )
}

private fun Map<String, Any?>.requiredInt(
    key: String,
    location: String,
): Int {
    return optionalInt(
        key = key,
        location = location,
    ) ?: throw SurveyYamlException(
        "$location.$key is required."
    )
}

private fun Map<String, Any?>.optionalInt(
    key: String,
    location: String,
): Int? {
    val value =
        this[key]
            ?: return null

    return when (value) {
        is Int ->
            value

        is Long ->
            value.toInt()

        is Number ->
            value.toInt()

        else ->
            throw SurveyYamlException(
                "$location.$key must be an integer."
            )
    }
}

private fun Map<String, Any?>.optionalDouble(
    key: String,
    location: String,
): Double? {
    val value =
        this[key]
            ?: return null

    return when (value) {
        is Number ->
            value.toDouble()

        else ->
            throw SurveyYamlException(
                "$location.$key must be a number."
            )
    }
}

private fun Map<String, Any?>.optionalBoolean(
    key: String,
    location: String,
): Boolean? {
    val value =
        this[key]
            ?: return null

    return value as? Boolean
        ?: throw SurveyYamlException(
            "$location.$key must be a boolean."
        )
}

private fun Map<String, Any?>.optionalStringList(
    key: String,
    location: String,
): List<String> {
    val list =
        optionalList(
            key = key,
            location = location,
        )

    return list.mapIndexed {
            index,
            value,
        ->

        value as? String
            ?: throw SurveyYamlException(
                "$location.$key[$index] must be a string."
            )
    }
}

private fun Map<String, Any?>.requiredLocalizedText(
    key: String,
    location: String,
): LocalizedText {
    val value =
        this[key]
            ?: throw SurveyYamlException(
                "$location.$key is required."
            )

    return parseLocalizedText(
        value = value,
        location = "$location.$key",
    )
}

private fun Map<String, Any?>.optionalLocalizedText(
    key: String,
    location: String,
): LocalizedText? {
    val value =
        this[key]
            ?: return null

    return parseLocalizedText(
        value = value,
        location = "$location.$key",
    )
}

/**
 * LocalizedText accepts either:
 *
 * title: Maize Farmer Survey
 *
 * or:
 *
 * title:
 *   default: Maize Farmer Survey
 *   translations:
 *     sw: ...
 */
private fun parseLocalizedText(
    value: Any?,
    location: String,
): LocalizedText {
    if (value is String) {
        if (value.isBlank()) {
            throw SurveyYamlException(
                "$location must not be blank."
            )
        }

        return LocalizedText(
            default = value
        )
    }

    val map =
        value.asStringMap(
            location = location
        )

    val defaultText =
        map.requiredString(
            key = "default",
            location = location,
        )

    val translationMap =
        map.optionalMap(
            key = "translations",
            location = location,
        )
            ?.mapValues {
                    entry ->

                entry.value as? String
                    ?: throw SurveyYamlException(
                        "$location.translations.${entry.key} must be a string."
                    )
            }
            ?: emptyMap()

    return LocalizedText(
        default = defaultText,
        translations = translationMap,
    )
}