package com.negi.surveycore.ai.backend.litertlm

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.negi.surveycore.ai.backend.GenerationRequest
import com.negi.surveycore.ai.backend.GenerationResult
import com.negi.surveycore.ai.backend.TextGenerationBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * LiteRT-LM implementation of TextGenerationBackend.
 *
 * The backend owns one LiteRT-LM Engine for the lifetime of this object.
 * A fresh Conversation is created for every generation request so that
 * survey evaluations do not accidentally share hidden conversation state.
 *
 * Survey-specific prompting and protocol parsing remain outside this class.
 */
class LiteRtLmBackend(
    private val modelPath: String,
    private val cacheDir: String? = null,
) : TextGenerationBackend {

    override val backendId: String =
        "litert-lm"

    private var engine: Engine? =
        null

    private val generationMutex =
        Mutex()

    /**
     * Loads the LiteRT-LM model.
     *
     * Model loading is performed away from the Android main thread because
     * initialization may take several seconds for a large model.
     */
    override suspend fun initialize() {
        if (engine != null) {
            return
        }

        withContext(Dispatchers.IO) {
            val config =
                EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),
                    cacheDir = cacheDir,
                )

            val newEngine =
                Engine(config)

            try {
                newEngine.initialize()
                engine = newEngine
            } catch (throwable: Throwable) {
                newEngine.close()
                throw throwable
            }
        }
    }

    /**
     * Generates one independent response.
     *
     * A fresh Conversation is deliberately created for each request.
     * All required state must therefore be supplied explicitly through
     * GenerationRequest.
     */
    override suspend fun generate(
        request: GenerationRequest,
    ): GenerationResult {
        val activeEngine =
            checkNotNull(engine) {
                "LiteRT-LM backend is not initialized."
            }

        require(
            request.maxOutputTokens > 0
        ) {
            "maxOutputTokens must be greater than zero."
        }

        require(
            request.temperature >= 0.0f
        ) {
            "temperature must not be negative."
        }

        return generationMutex.withLock {
            withContext(Dispatchers.IO) {
                val systemInstruction =
                    if (
                        request.systemInstruction
                            .isBlank()
                    ) {
                        null
                    } else {
                        Contents.of(
                            request.systemInstruction
                        )
                    }

                /*
                 * A temperature of zero is used by SurveyCore for
                 * deterministic evaluation. LiteRT-LM uses a TOP_P sampler
                 * whenever SamplerConfig is supplied, so topK=1 is used for
                 * the zero-temperature path to make decoding explicitly
                 * greedy rather than relying on temperature alone.
                 *
                 * Non-zero temperatures retain the broader sampling settings
                 * used for generative requests.
                 */
                val deterministic =
                    request.temperature == 0.0f

                val samplerConfig =
                    SamplerConfig(
                        topK =
                            if (
                                deterministic
                            ) {
                                1
                            } else {
                                40
                            },
                        topP =
                            if (
                                deterministic
                            ) {
                                1.0
                            } else {
                                0.95
                            },
                        temperature =
                            request.temperature
                                .toDouble(),
                        seed = 0,
                    )

                val conversationConfig =
                    ConversationConfig(
                        systemInstruction =
                            systemInstruction,
                        samplerConfig =
                            samplerConfig,
                    )

                activeEngine
                    .createConversation(
                        conversationConfig
                    )
                    .use { conversation ->
                        val message =
                            conversation.sendMessage(
                                text =
                                    request.prompt,
                                maxOutputToken =
                                    request.maxOutputTokens,
                            )

                        GenerationResult(
                            text =
                                message
                                    .toString()
                                    .trim()
                        )
                    }
            }
        }
    }

    override fun isReady(): Boolean {
        return engine != null
    }

    /**
     * Releases native LiteRT-LM resources.
     */
    override fun close() {
        engine?.close()
        engine = null
    }
}