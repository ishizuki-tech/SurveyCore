package com.negi.surveycore.asr

/**
 * Plain ASR result plus timing information useful for on-device benchmarks.
 */
data class TranscriptionResult(
    val text: String,
    val audioDurationMs: Long,
    val inferenceDurationMs: Long,
) {

    /**
     * Real-time factor. Values below 1.0 are faster than real time.
     */
    val realtimeFactor: Double
        get() {
            if (
                audioDurationMs <= 0L
            ) {
                return 0.0
            }

            return inferenceDurationMs.toDouble() /
                    audioDurationMs.toDouble()
        }
}
