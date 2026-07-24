package com.vieneu.reader.playback

/**
 * The streaming buffer-ahead rule: playback of a chapter that isn't fully
 * generated yet may only run/continue once enough sentences have been
 * generated ahead of the current playback position.
 *
 * Two thresholds, not one — this is the hysteresis that matters:
 * - **Cold start** (nothing has played yet this session): require a full
 *   20-sentence cushion (or whatever's left in the chapter) before the
 *   first sentence plays, so playback doesn't immediately out-run
 *   generation and stall a few seconds in.
 * - **Warmed up** (already played at least one sentence): only require 1
 *   sentence ahead to resume after a catch-up pause. Reusing the cold-start
 *   threshold here would mean every mid-chapter stall — generation
 *   momentarily falling behind real-time playback — had to rebuild the
 *   *entire* 20-sentence buffer before resuming, turning a brief catch-up
 *   into a multi-minute stall, since generation on this hardware isn't
 *   necessarily much faster than real-time.
 *
 * Pure function of counts — no I/O, no coroutines — so the rule itself is
 * trivially unit-testable independent of the real generation service or
 * player.
 */
object BufferGate {
    private const val COLD_START_LOOKAHEAD_SENTENCES = 20
    private const val WARM_LOOKAHEAD_SENTENCES = 1

    /** How many sentences must be generated-ahead of [currentIndex] before playback may run/resume. */
    fun requiredLookahead(totalSentences: Int, currentIndex: Int, warmedUp: Boolean): Int {
        val remaining = (totalSentences - currentIndex).coerceAtLeast(0)
        val target = if (warmedUp) WARM_LOOKAHEAD_SENTENCES else COLD_START_LOOKAHEAD_SENTENCES
        return minOf(target, remaining)
    }

    /**
     * @param totalSentences sentences in the chapter
     * @param currentIndex the sentence about to play / currently playing (0-based)
     * @param lastGeneratedIndex highest sentence index generated so far, or -1 if none
     * @param warmedUp whether at least one sentence has already played in this playback session
     */
    fun mayPlay(totalSentences: Int, currentIndex: Int, lastGeneratedIndex: Int, warmedUp: Boolean): Boolean {
        if (currentIndex >= totalSentences) return false
        val generatedAhead = (lastGeneratedIndex - currentIndex + 1).coerceAtLeast(0)
        val required = requiredLookahead(totalSentences, currentIndex, warmedUp)
        return generatedAhead >= required
    }
}
