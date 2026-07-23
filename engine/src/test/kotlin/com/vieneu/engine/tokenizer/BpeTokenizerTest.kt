package com.vieneu.engine.tokenizer

import java.io.File
import kotlin.test.assertEquals
import org.junit.Test

/**
 * Expected ids below are the real output of `tokenizers.Tokenizer.encode(text,
 * add_special_tokens=False).ids` on the real tokenizer.json bundled in
 * engine/src/main/assets — captured while porting (see design spec's
 * shift-left notes). If this test passes, the Kotlin BPE tokenizer is
 * provably byte-identical to the Python `tokenizers` output VieNeu-TTS
 * actually ships with, not just "produces some ids."
 */
class BpeTokenizerTest {
    private val tokenizer by lazy {
        val json = File("src/main/assets/onnx_update/tokenizer.json").readText()
        BpeTokenizer.fromJson(json)
    }

    @Test
    fun encodesPhonemeStringsIdenticallyToPythonTokenizers() {
        val cases = listOf(
            "tʃˈɔŋ mˈaː2n mˈyə mˈuə2 hˈaː6 ɹˈe2n vˈaːŋ sˈəɜm sˈɛɜt̪, mˈo6t̪ tʃˈiɛɜc pˈɔːɹ tʃˈɛ mˈa2w ɗˈɛn tʃˈa6j tʃˈen ɗˈyə2ŋ ˈəː4 vˈu2ŋ kwˈe." to
                listOf(
                    160, 319, 325, 306, 303, 352, 325, 141, 327, 94, 154, 352, 325, 165, 308, 352, 325, 161, 308, 94,
                    347, 325, 141, 327, 98, 76, 317, 325, 145, 94, 154, 360, 325, 141, 327, 303, 357, 325, 308, 311,
                    153, 357, 325, 310, 311, 160, 330, 88, 352, 325, 155, 98, 160, 330, 358, 319, 325, 149, 310, 311,
                    143, 355, 325, 306, 327, 317, 358, 319, 325, 310, 352, 325, 141, 94, 163, 76, 307, 325, 310, 154,
                    358, 319, 325, 141, 98, 150, 358, 319, 325, 145, 154, 76, 307, 325, 165, 308, 94, 303, 76, 325,
                    308, 327, 96, 360, 325, 161, 94, 303, 350, 163, 325, 145, 90,
                ),
            "zˈaːɜ ˈɛɜt̪ pˈe nˈam tʃˈam hˈom nˈaj lˌaː2 bˈoɜn ŋˈi2n hˈaːj tʃˈam fˈəɪ4 nˈam ɗˈiɛ4m." to
                listOf(
                    166, 325, 141, 327, 311, 76, 325, 310, 311, 160, 330, 355, 325, 145, 353, 325, 141, 153, 358, 319,
                    325, 141, 153, 347, 325, 155, 153, 353, 325, 141, 150, 351, 326, 141, 327, 94, 342, 325, 155, 311,
                    154, 76, 303, 325, 149, 94, 154, 347, 325, 141, 327, 150, 358, 319, 325, 141, 153, 346, 325, 308,
                    314, 96, 353, 325, 141, 153, 76, 307, 325, 149, 310, 96, 153, 90,
                ),
            "Xin chào" to listOf(132, 149, 154, 343, 148, 239, 204, 155),
            "a" to listOf(141),
        )

        for ((text, expected) in cases) {
            assertEquals(expected, tokenizer.encode(text), "mismatch for input: $text")
        }
    }
}
