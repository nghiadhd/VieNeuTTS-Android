package com.vieneu.reader.playback

/**
 * The streaming buffer-ahead rule from the design spec (§4): playback of a
 * chapter that isn't fully generated yet may only run/continue once enough
 * of the *remaining* (not-yet-heard) sentences have been generated ahead of
 * the current playback position.
 *
 * Pure function of three counts — no I/O, no coroutines — so the rule
 * itself is trivially unit-testable independent of the real generation
 * service or player.
 */
object BufferGate {
    /** How many sentences must be generated-ahead of [currentIndex] before playback may run. */
    fun requiredLookahead(totalSentences: Int, currentIndex: Int): Int {
        val remaining = (totalSentences - currentIndex).coerceAtLeast(0)
        return (remaining + 3) / 4 // ceil(remaining / 4)
    }

    /**
     * @param totalSentences sentences in the chapter
     * @param currentIndex the sentence about to play / currently playing (0-based)
     * @param lastGeneratedIndex highest sentence index generated so far, or -1 if none
     */
    fun mayPlay(totalSentences: Int, currentIndex: Int, lastGeneratedIndex: Int): Boolean {
        if (currentIndex >= totalSentences) return false
        val generatedAhead = (lastGeneratedIndex - currentIndex + 1).coerceAtLeast(0)
        val required = requiredLookahead(totalSentences, currentIndex)
        return generatedAhead >= required
    }
}
