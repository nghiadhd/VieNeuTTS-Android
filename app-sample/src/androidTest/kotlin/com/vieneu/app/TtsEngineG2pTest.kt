package com.vieneu.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vieneu.engine.TtsEngine
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Shift-left check for the G2P/uniffi/JNI port: these expected strings are
 * byte-for-byte outputs of the real Python `sea_g2p` package on the same
 * inputs (verified on host during the Rust port, see
 * docs/superpowers/specs/2026-07-23-tts-engine-core-design.md §"shift-left
 * test"). If this test passes, the Android phonemizer output is provably
 * identical to VieNeu-TTS's existing Python pipeline, not just "runs without
 * crashing."
 */
@RunWith(AndroidJUnit4::class)
class TtsEngineG2pTest {

    @Test
    fun phonemize_matchesPythonSeaG2pOutput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = TtsEngine.create(context)

        val cases = mapOf(
            "Trong màn mưa mùa hạ rền vang sấm sét, một chiếc Porche màu đen chạy trên đường ở vùng quê." to
                "tʃˈɔŋ mˈaː2n mˈyə mˈuə2 hˈaː6 ɹˈe2n vˈaːŋ sˈəɜm sˈɛɜt̪, mˈo6t̪ tʃˈiɛɜc pˈɔːɹ tʃˈɛ mˈa2w ɗˈɛn tʃˈa6j tʃˈen ɗˈyə2ŋ ˈəː4 vˈu2ŋ kwˈe.",
            "Giá SP500 hôm nay là 4.200,5 điểm." to
                "zˈaːɜ ˈɛɜt̪ pˈe nˈam tʃˈam hˈom nˈaj lˌaː2 bˈoɜn ŋˈi2n hˈaːj tʃˈam fˈəɪ4 nˈam ɗˈiɛ4m.",
            "Massachusetts, là tiến sĩ của Massachusetts, tiến sĩ song học vị toán học và máy tính." to
                "mˌæsɐtʃˈuːsɪts, lˌaː2 t̪ˈiɛɜn sˈi5 kˌuə4 mˌæsɐtʃˈuːsɪts, t̪ˈiɛɜn sˈi5 sˈɔŋ hˈɔ6k vˈi6 t̪wˈaːɜn hˈɔ6k vˌaː2 mˈaɜj t̪ˈiɜɲ.",
        )

        for ((input, expected) in cases) {
            assertEquals(expected, engine.phonemize(input))
        }
    }
}
