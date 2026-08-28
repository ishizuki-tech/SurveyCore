package com.negi.surveycore.ai.backend

/**
 * Generic result returned by a text-generation backend.
 */
data class GenerationResult(
    val text: String,
)