package com.vieneu.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vieneu.engine.TtsEngine
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Expected output is the real `phonemize_text_with_emotions` output from
 * Python on the same input (see design spec's shift-left notes) — validates
 * the inline emotion-cue feature ([cười]/[hắng giọng] -> <|emotion_k|>) on
 * real device G2P, which JVM host tests structurally can't exercise (the
 * Rust .so is Android-only).
 */
@RunWith(AndroidJUnit4::class)
class EmotionPhonemizeTest {
    @Test
    fun phonemize_preservesEmotionCuesAsControlTokens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = TtsEngine.create(context)

        val text = "Nghe hay quá đi [cười]. Để mình nói tiếp [hắng giọng]."
        val expected = "ŋˈɛ hˈaj kwˈaːɜ ɗˈi <|emotion_1|>. ɗˌe4 mˈi2ɲ nˈɔɜj t̪ˈiɛɜp <|emotion_3|>."

        assertEquals(expected, engine.phonemize(text))
    }
}
