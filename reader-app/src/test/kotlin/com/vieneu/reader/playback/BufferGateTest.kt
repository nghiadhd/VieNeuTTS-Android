package com.vieneu.reader.playback

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class BufferGateTest {
    @Test
    fun requiredLookahead_matchesDesignSpecExample() {
        // "Chương có 20 chunk, đã nghe hết chunk 1-5 (đang ở chunk 6, index 5).
        //  Remaining = chunk 6..20 = 15 chunk. 1/4 remaining = 15/4 ≈ 4."
        assertEquals(4, BufferGate.requiredLookahead(totalSentences = 20, currentIndex = 5))
    }

    @Test
    fun requiredLookahead_shrinksAsChapterEndsNears() {
        assertEquals(38, BufferGate.requiredLookahead(totalSentences = 150, currentIndex = 0))
        assertEquals(1, BufferGate.requiredLookahead(totalSentences = 150, currentIndex = 147))
        assertEquals(0, BufferGate.requiredLookahead(totalSentences = 150, currentIndex = 150))
    }

    @Test
    fun mayPlay_falseWhenNothingGeneratedYet() {
        assertFalse(BufferGate.mayPlay(totalSentences = 20, currentIndex = 0, lastGeneratedIndex = -1))
    }

    @Test
    fun mayPlay_trueOnceRequiredLookaheadIsGenerated() {
        // required = ceil(15/4) = 4 at currentIndex=5; sentences 5,6,7,8 generated (lastGeneratedIndex=8) -> ahead=4.
        assertTrue(BufferGate.mayPlay(totalSentences = 20, currentIndex = 5, lastGeneratedIndex = 8))
        assertFalse(BufferGate.mayPlay(totalSentences = 20, currentIndex = 5, lastGeneratedIndex = 7))
    }

    @Test
    fun mayPlay_pausesWhenPlaybackCatchesUpToGeneration() {
        // Fully caught up: nothing generated beyond the current sentence.
        assertFalse(BufferGate.mayPlay(totalSentences = 20, currentIndex = 10, lastGeneratedIndex = 10))
    }

    @Test
    fun mayPlay_alwaysTrueForAFullyGeneratedChapter() {
        for (i in 0 until 150) {
            assertTrue(BufferGate.mayPlay(totalSentences = 150, currentIndex = i, lastGeneratedIndex = 149))
        }
    }

    @Test
    fun mayPlay_falseAtOrPastChapterEnd() {
        assertFalse(BufferGate.mayPlay(totalSentences = 20, currentIndex = 20, lastGeneratedIndex = 19))
    }
}
