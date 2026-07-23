package com.vieneu.engine.model

import com.vieneu.engine.tokenizer.BpeTokenizer
import com.vieneu.engine.tokenizer.MiniJson
import com.vieneu.engine.tokenizer.asArr
import com.vieneu.engine.tokenizer.asObj
import java.io.File
import kotlin.test.assertEquals
import org.junit.Test

/**
 * Expected values below are from running the real `_build_rows` (numpy) on
 * the same phoneme string + "Thái Sơn" preset voice codes (see design spec's
 * shift-left notes).
 */
class PromptBuilderTest {
    private val config by lazy {
        VieNeuConfig.fromJson(File("src/main/assets/onnx_update/config.json").readText())
    }
    private val tokenizer by lazy {
        BpeTokenizer.fromJson(File("src/main/assets/onnx_update/tokenizer.json").readText())
    }
    private val thaiSonCodes by lazy {
        val json = MiniJson.parse(File("src/main/assets/voices_v3_turbo.json").readText()).asObj()
        val presets = json.getValue("presets").asObj()
        val codes = presets.getValue("Thái Sơn").asObj().getValue("codes").asArr()
        Array(codes.size) { r -> codes[r].asArr().map { (it as Double).toInt() }.toIntArray() }
    }

    @Test
    fun buildRows_matchesPython() {
        val phonemes = "zˈaːɜ ˈɛɜt̪ pˈe nˈam tʃˈam hˈom nˈaj lˌaː2 bˈoɜn ŋˈi2n hˈaːj tʃˈam fˈəɪ4 nˈam ɗˈiɛ4m."
        val styleId = config.resolveStyleId("doc_truyen")

        val rows = PromptBuilder.buildRows(phonemes, tokenizer, styleId, thaiSonCodes, config)

        assertEquals(126, rows.size) // 77 text rows + 49 ref rows
        val padRow = IntArray(config.nVq + 1) { config.audioPad }

        assertEquals(18, rows[0][0]) // style id (doc_truyen)
        assertEquals(padRow.drop(1), rows[0].drop(1))

        assertEquals(4, rows[76][0]) // last text row = TEXT_PROMPT_END
        assertEquals(padRow.drop(1), rows[76].drop(1))

        val expectedFirstRef = listOf(7, 824, 180, 967, 87, 422, 818, 715, 1006, 977, 275, 457, 415, 290, 973, 970, 895)
        assertEquals(expectedFirstRef, rows[77].toList())

        val expectedLastRef = listOf(7, 670, 494, 753, 130, 816, 160, 914, 591, 794, 259, 107, 917, 129, 151, 12, 675)
        assertEquals(expectedLastRef, rows[125].toList())

        var checksum = 0L
        for (row in rows) for (v in row) checksum += v
        assertEquals(1_693_482L, checksum)
    }
}
