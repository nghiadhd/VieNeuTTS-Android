package com.vieneu.reader.playback

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class BufferGateTest {
    @Test
    fun requiredLookahead_coldStartIsFixedAt20Sentences() {
        assertEquals(20, BufferGate.requiredLookahead(totalSentences = 150, currentIndex = 0, warmedUp = false))
        assertEquals(20, BufferGate.requiredLookahead(totalSentences = 264, currentIndex = 5, warmedUp = false))
    }

    @Test
    fun requiredLookahead_warmedUpOnlyNeedsOneSentence() {
        assertEquals(1, BufferGate.requiredLookahead(totalSentences = 150, currentIndex = 5, warmedUp = true))
    }

    @Test
    fun requiredLookahead_shrinksAsChapterEndsNears() {
        assertEquals(20, BufferGate.requiredLookahead(totalSentences = 150, currentIndex = 0, warmedUp = false))
        assertEquals(3, BufferGate.requiredLookahead(totalSentences = 150, currentIndex = 147, warmedUp = false))
        assertEquals(0, BufferGate.requiredLookahead(totalSentences = 150, currentIndex = 150, warmedUp = false))
        assertEquals(0, BufferGate.requiredLookahead(totalSentences = 150, currentIndex = 150, warmedUp = true))
    }

    @Test
    fun mayPlay_falseWhenNothingGeneratedYet() {
        assertFalse(BufferGate.mayPlay(totalSentences = 150, currentIndex = 0, lastGeneratedIndex = -1, warmedUp = false))
    }

    @Test
    fun mayPlay_coldStart_trueOnceFullBufferIsGenerated() {
        // required = min(20, 145) = 20 at currentIndex=5; sentences 5..24 generated (lastGeneratedIndex=24) -> ahead=20.
        assertTrue(BufferGate.mayPlay(totalSentences = 150, currentIndex = 5, lastGeneratedIndex = 24, warmedUp = false))
        assertFalse(BufferGate.mayPlay(totalSentences = 150, currentIndex = 5, lastGeneratedIndex = 23, warmedUp = false))
    }

    @Test
    fun mayPlay_warmedUp_trueWithJustOneSentenceAhead() {
        // Same position as the cold-start case above, but warmed up: only 1 sentence ahead needed,
        // not the full 20 — this is the fix for mid-chapter catch-up pauses turning into long stalls.
        assertTrue(BufferGate.mayPlay(totalSentences = 150, currentIndex = 5, lastGeneratedIndex = 5, warmedUp = true))
        assertFalse(BufferGate.mayPlay(totalSentences = 150, currentIndex = 5, lastGeneratedIndex = 4, warmedUp = true))
    }

    @Test
    fun mayPlay_pausesWhenPlaybackCatchesUpToGeneration() {
        // Fully caught up: not even the current sentence is generated yet — even warmed up
        // (needing only 1 sentence ahead), there's nothing to play.
        assertFalse(BufferGate.mayPlay(totalSentences = 20, currentIndex = 10, lastGeneratedIndex = 9, warmedUp = true))
    }

    @Test
    fun mayPlay_alwaysTrueForAFullyGeneratedChapter() {
        for (i in 0 until 150) {
            assertTrue(BufferGate.mayPlay(totalSentences = 150, currentIndex = i, lastGeneratedIndex = 149, warmedUp = false))
            assertTrue(BufferGate.mayPlay(totalSentences = 150, currentIndex = i, lastGeneratedIndex = 149, warmedUp = true))
        }
    }

    @Test
    fun mayPlay_falseAtOrPastChapterEnd() {
        assertFalse(BufferGate.mayPlay(totalSentences = 20, currentIndex = 20, lastGeneratedIndex = 19, warmedUp = false))
    }
}
