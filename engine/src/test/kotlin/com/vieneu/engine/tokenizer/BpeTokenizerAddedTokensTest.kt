package com.vieneu.engine.tokenizer

import java.io.File
import kotlin.test.assertEquals
import org.junit.Test

/**
 * Ground truth: real `tokenizers.Tokenizer.encode(text,
 * add_special_tokens=False).ids` on a phoneme string with an embedded
 * `<|emotion_1|>` control token (see design spec's shift-left notes) — this
 * is what `phonemize_text_with_emotions` output actually gets fed through,
 * so the tokenizer must resolve `<|emotion_1|>` to its single vocab id (9),
 * not BPE-encode it character by character.
 */
class BpeTokenizerAddedTokensTest {
    private val tokenizer by lazy {
        BpeTokenizer.fromJson(File("src/main/assets/onnx_update/tokenizer.json").readText())
    }

    @Test
    fun resolvesEmotionControlTokenAsASingleId() {
        val text = "xˈin tʃˈaː2w <|emotion_1|> nˈaːj bˈan."
        val expected = listOf(164, 325, 149, 154, 358, 319, 325, 141, 327, 94, 163, 76, 9, 353, 325, 141, 327, 150, 342, 325, 141, 154, 90)
        assertEquals(expected, tokenizer.encode(text))
    }
}
