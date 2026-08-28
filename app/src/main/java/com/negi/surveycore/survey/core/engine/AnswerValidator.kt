package com.negi.surveycore.survey.core.engine

import com.negi.surveycore.survey.core.model.AnswerDefinition
import com.negi.surveycore.survey.core.model.AnswerType

/**
 * Performs deterministic validation that does not require AI.
 */
object AnswerValidator {

    fun validate(
        answer: String,
        definition: AnswerDefinition,
    ): ValidationResult {
        val trimmed = answer.trim()

        if (definition.required && trimmed.isEmpty()) {
            return ValidationResult.Invalid(
                "An answer is required."
            )
        }

        if (trimmed.isEmpty()) {
            return ValidationResult.Valid
        }

        val typeResult =
            validateType(
                answer = trimmed,
                definition = definition,
            )

        if (typeResult is ValidationResult.Invalid) {
            return typeResult
        }

        val rules = definition.deterministicValidation

        if (
            rules.minLength != null &&
            trimmed.length < rules.minLength
        ) {
            return ValidationResult.Invalid(
                "The answer must contain at least ${rules.minLength} characters."
            )
        }

        if (
            rules.maxLength != null &&
            trimmed.length > rules.maxLength
        ) {
            return ValidationResult.Invalid(
                "The answer must contain no more than ${rules.maxLength} characters."
            )
        }

        val numericValue =
            when (definition.type) {
                AnswerType.INTEGER,
                AnswerType.DECIMAL,
                    -> trimmed.toDoubleOrNull()

                else -> null
            }

        if (
            numericValue != null &&
            rules.minNumber != null &&
            numericValue < rules.minNumber
        ) {
            return ValidationResult.Invalid(
                "The answer must be at least ${rules.minNumber}."
            )
        }

        if (
            numericValue != null &&
            rules.maxNumber != null &&
            numericValue > rules.maxNumber
        ) {
            return ValidationResult.Invalid(
                "The answer must be no greater than ${rules.maxNumber}."
            )
        }

        return ValidationResult.Valid
    }

    private fun validateType(
        answer: String,
        definition: AnswerDefinition,
    ): ValidationResult {
        return when (definition.type) {
            AnswerType.TEXT ->
                ValidationResult.Valid

            AnswerType.INTEGER ->
                if (answer.toIntOrNull() != null) {
                    ValidationResult.Valid
                } else {
                    ValidationResult.Invalid(
                        "The answer must be an integer."
                    )
                }

            AnswerType.DECIMAL ->
                if (answer.toDoubleOrNull() != null) {
                    ValidationResult.Valid
                } else {
                    ValidationResult.Invalid(
                        "The answer must be a number."
                    )
                }

            AnswerType.BOOLEAN ->
                if (
                    answer.equals("true", ignoreCase = true) ||
                    answer.equals("false", ignoreCase = true)
                ) {
                    ValidationResult.Valid
                } else {
                    ValidationResult.Invalid(
                        "The answer must be true or false."
                    )
                }

            AnswerType.SINGLE_CHOICE -> {
                val validOption =
                    definition.options.any {
                        it.id == answer
                    }

                if (validOption) {
                    ValidationResult.Valid
                } else {
                    ValidationResult.Invalid(
                        "The answer is not a valid option."
                    )
                }
            }

            AnswerType.MULTI_CHOICE ->
                ValidationResult.Valid
        }
    }
}

sealed interface ValidationResult {

    data object Valid : ValidationResult

    data class Invalid(
        val message: String,
    ) : ValidationResult
}