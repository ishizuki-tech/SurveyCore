package com.negi.surveycore.asr.whispercpp

/**
 * Low-level JNI bridge to the embedded whisper.cpp runtime.
 *
 * This object exposes native primitives only. Audio capture, survey semantics,
 * and UI state belong in higher layers.
 */
internal object WhisperCppNative {

    init {
        System.loadLibrary(
            "survey_whisper"
        )
    }

    /**
     * Returns diagnostic information about the linked whisper.cpp runtime.
     */
    external fun nativeInfo(): String

    /**
     * Loads a Whisper model and returns an opaque persistent native handle.
     */
    external fun create(
        modelPath: String,
    ): Long

    /**
     * Transcribes normalized 16 kHz mono floating-point PCM.
     */
    external fun transcribe(
        handle: Long,
        samples: FloatArray,
        sampleRateHz: Int,
        threadCount: Int,
    ): String

    /**
     * Releases a persistent native Whisper instance.
     */
    external fun close(
        handle: Long,
    )
}
