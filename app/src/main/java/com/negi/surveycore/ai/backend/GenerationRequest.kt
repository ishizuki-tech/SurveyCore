package com.negi.surveycore.ai.backend

/**
 * Generic text-generation request.
 *
 * This type intentionally contains no survey-specific concepts.
 * It can be consumed by LiteRT-LM, llama.cpp, a fake backend,
 * or another text-generation runtime.
 */
data class GenerationRequest(
    val systemInstruction: String,
    val prompt: String,
    val maxOutputTokens: Int = 96,
    val temperature: Float = 0.0f,
)