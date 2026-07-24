package com.vieneu.engine

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/**
 * Host-testable subset of [EmotionPhonemizer]: tag detection/splitting, no
 * real G2P call needed (SeaG2p's JNI binding is Android-only — the full
 * phonemize() path is validated on-device in app-sample's
 * EmotionPhonemizeTest against real Python output).
 */
class EmotionPhonemizerSplitTest {
    @Test
    fun tagToken_resolvesKnownVietnameseAndEnglishSpellings() {
        assertEquals("<|emotion_1|>", EmotionPhonemizer.tagToken("[cười]"))
        assertEquals("<|emotion_1|>", EmotionPhonemizer.tagToken("[chuckle]"))
        assertEquals("<|emotion_2|>", EmotionPhonemizer.tagToken("[thở dài]"))
        assertEquals("<|emotion_3|>", EmotionPhonemizer.tagToken("[hắng giọng]"))
        assertEquals("<|emotion_1|>", EmotionPhonemizer.tagToken("<|emotion_1|>")) // pass-through
    }

    @Test
    fun tagToken_returnsNullForUnrecognizedBracketedText() {
        assertNull(EmotionPhonemizer.tagToken("[không phải emotion]"))
    }

    @Test
    fun splitKeepingDelimiters_findsBothTagsInText() {
        val text = "Nghe hay quá đi [cười]. Để mình nói tiếp [hắng giọng]."
        val parts = EmotionPhonemizer.splitKeepingDelimiters(text)
        assertEquals(5, parts.size)
        assertEquals("[cười]", parts[1])
        assertEquals("[hắng giọng]", parts[3])
    }

    @Test
    fun splitKeepingDelimiters_noTagsReturnsWholeTextAsOneElement() {
        val text = "Không có tag nào ở đây."
        assertEquals(listOf(text), EmotionPhonemizer.splitKeepingDelimiters(text))
    }
}
