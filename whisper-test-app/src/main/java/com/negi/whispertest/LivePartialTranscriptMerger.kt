package com.negi.whispertest

import java.util.Locale

/**
 * Assembles fixed-window Whisper partial transcripts into one stable line.
 *
 * Live partial inference is performed on a short rolling window instead of the
 * entire utterance. Older accepted text is therefore treated as committed.
 * Each new window is aligned against the suffix of the displayed text and only
 * the overlapping tail is allowed to change. This keeps most of the line still
 * while Whisper revises the most recent words.
 *
 * The final base.en transcription remains authoritative.
 */
internal class LivePartialTranscriptMerger {

    private var activeUtteranceId: Long? = null
    private var acceptedWords: List<String> = emptyList()

    fun reset() {
        activeUtteranceId = null
        acceptedWords = emptyList()
    }

    fun startUtterance(utteranceId: Long) {
        if (activeUtteranceId != utteranceId) {
            activeUtteranceId = utteranceId
            acceptedWords = emptyList()
        }
    }

    fun mergePartial(
        utteranceId: Long,
        incomingText: String,
    ): String {
        startUtterance(utteranceId)

        val incomingWords = splitWords(normalizeWhitespace(incomingText))
        if (incomingWords.isEmpty()) {
            return acceptedWords.joinToString(" ")
        }

        if (acceptedWords.isEmpty()) {
            acceptedWords = incomingWords
            return acceptedWords.joinToString(" ")
        }

        if (sameWords(acceptedWords, incomingWords)) {
            acceptedWords = incomingWords
            return acceptedWords.joinToString(" ")
        }

        if (isWordPrefix(acceptedWords, incomingWords)) {
            acceptedWords = incomingWords
            return acceptedWords.joinToString(" ")
        }

        if (isWordPrefix(incomingWords, acceptedWords)) {
            return acceptedWords.joinToString(" ")
        }

        val overlap = findBestSuffixPrefixOverlap(acceptedWords, incomingWords)

        if (overlap > 0) {
            val committedCount = acceptedWords.size - overlap
            acceptedWords =
                acceptedWords.take(committedCount) + incomingWords
            return acceptedWords.joinToString(" ")
        }

        // No reliable overlap was found. Keep the stable prefix and allow only
        // a small tail to be rewritten. This prevents the whole line from
        // jumping because of one unstable rolling-window hypothesis.
        val committedCount =
            (acceptedWords.size - MAXIMUM_MUTABLE_TAIL_WORDS).coerceAtLeast(0)

        acceptedWords =
            acceptedWords.take(committedCount) + incomingWords

        return acceptedWords.joinToString(" ")
    }

    fun finalizeUtterance(
        utteranceId: Long,
        finalText: String,
    ): String {
        val normalized = normalizeWhitespace(finalText)

        if (activeUtteranceId == utteranceId) {
            activeUtteranceId = null
            acceptedWords = emptyList()
        }

        return normalized
    }

    private fun findBestSuffixPrefixOverlap(
        previous: List<String>,
        incoming: List<String>,
    ): Int {
        val maximum = minOf(previous.size, incoming.size, MAXIMUM_OVERLAP_WORDS)

        for (overlap in maximum downTo 1) {
            var matches = 0

            for (index in 0 until overlap) {
                val previousWord =
                    canonicalWord(previous[previous.size - overlap + index])
                val incomingWord = canonicalWord(incoming[index])

                if (previousWord == incomingWord) {
                    matches += 1
                }
            }

            val requiredMatches =
                if (overlap <= 2) {
                    overlap
                } else {
                    overlap - 1
                }

            if (matches >= requiredMatches) {
                return overlap
            }
        }

        return 0
    }

    private fun normalizeWhitespace(text: String): String =
        text
            .trim()
            .replace(WHITESPACE_REGEX, " ")

    private fun splitWords(text: String): List<String> =
        if (text.isBlank()) {
            emptyList()
        } else {
            text.split(' ')
        }

    private fun sameWords(
        left: List<String>,
        right: List<String>,
    ): Boolean =
        left.size == right.size &&
            left.indices.all { index ->
                canonicalWord(left[index]) == canonicalWord(right[index])
            }

    private fun isWordPrefix(
        prefix: List<String>,
        full: List<String>,
    ): Boolean {
        if (prefix.size > full.size) {
            return false
        }

        return prefix.indices.all { index ->
            canonicalWord(prefix[index]) == canonicalWord(full[index])
        }
    }

    private fun canonicalWord(word: String): String =
        word
            .lowercase(Locale.ROOT)
            .trim { character ->
                !character.isLetterOrDigit() && character != '\''
            }

    private companion object {
        val WHITESPACE_REGEX = Regex("\\s+")

        const val MAXIMUM_OVERLAP_WORDS = 12
        const val MAXIMUM_MUTABLE_TAIL_WORDS = 4
    }
}
