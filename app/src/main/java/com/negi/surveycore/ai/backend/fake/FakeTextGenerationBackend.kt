package com.negi.surveycore.ai.backend.fake

import com.negi.surveycore.ai.backend.GenerationRequest
import com.negi.surveycore.ai.backend.GenerationResult
import com.negi.surveycore.ai.backend.TextGenerationBackend

/**
 * Deterministic text-generation backend used for development and tests.
 *
 * This fake operates at the runtime boundary rather than at the SurveyAi
 * boundary. This allows ModelSurveyAi, prompt builders, and protocol parsers
 * to be exercised without loading a real language model.
 */
class FakeTextGenerationBackend(
    responses: List<String> = emptyList(),
    private val initializationFailure: Exception? = null,
    private val generationFailure: Exception? = null,
    override val backendId: String = "fake-text-generation",
) : TextGenerationBackend {

    private val queuedResponses =
        ArrayDeque(responses)

    private var ready =
        false

    private var closed =
        false

    var initializeCallCount: Int = 0
        private set

    var generateCallCount: Int = 0
        private set

    val requests: MutableList<GenerationRequest> =
        mutableListOf()

    /**
     * Adds another deterministic model response to the queue.
     */
    fun enqueueResponse(
        response: String,
    ) {
        check(!closed) {
            "Fake backend is already closed."
        }

        queuedResponses.addLast(
            response
        )
    }

    override suspend fun initialize() {
        check(!closed) {
            "Fake backend is already closed."
        }

        initializeCallCount += 1

        initializationFailure?.let {
            throw it
        }

        ready = true
    }

    override suspend fun generate(
        request: GenerationRequest,
    ): GenerationResult {
        check(!closed) {
            "Fake backend is already closed."
        }

        check(ready) {
            "Fake backend is not initialized."
        }

        generateCallCount += 1
        requests += request

        generationFailure?.let {
            throw it
        }

        check(queuedResponses.isNotEmpty()) {
            "Fake backend has no queued response."
        }

        return GenerationResult(
            text =
                queuedResponses.removeFirst()
        )
    }

    override fun isReady(): Boolean {
        return ready && !closed
    }

    override fun close() {
        ready = false
        closed = true
    }
}