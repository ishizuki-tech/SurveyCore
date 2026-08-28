package com.negi.surveycore.asr

/**
 * Runtime-neutral speech-recognition backend.
 *
 * Survey-specific behavior must not be implemented in this interface.
 */
interface SpeechRecognitionBackend {

    /**
     * Stable identifier for diagnostics and configuration.
     */
    val backendId: String

    /**
     * Input sample rate required by this backend.
     */
    val requiredSampleRateHz: Int

    /**
     * Initializes the underlying ASR runtime.
     */
    suspend fun initialize()

    /**
     * Transcribes one independent PCM floating-point audio buffer.
     *
     * Samples must be mono and normalized approximately to [-1, 1].
     */
    suspend fun transcribe(
        samples: FloatArray,
        sampleRateHz: Int = requiredSampleRateHz,
    ): TranscriptionResult

    /**
     * Returns true when the backend can accept transcription requests.
     */
    fun isReady(): Boolean

    /**
     * Releases runtime resources.
     *
     * Calling close more than once must be safe.
     */
    fun close()
}
