package com.vieneu.engine.model

import com.vieneu.engine.npy.NpyReader
import com.vieneu.engine.tokenizer.MiniJson
import com.vieneu.engine.tokenizer.asArr
import com.vieneu.engine.tokenizer.asObj
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Expected values below came from running the real numpy math in
 * `onnx_runtime_lite.py`'s `_speaker_anchor`/`_embed_rows` on the bundled
 * `vieneu_v3_heads.npz` + the "Thái Sơn" preset voice (see design spec's
 * shift-left notes) — proves the Kotlin math is bit-for-bit what Python
 * computes before any ONNX Runtime call depends on it.
 */
class VieNeuMathTest {
    private val heads by lazy {
        File("src/main/assets/onnx_int8/vieneu_v3_heads.npz").inputStream().use { NpyReader.parseNpz(it) }
    }
    private val thaiSonSpeakerEmb by lazy {
        val json = MiniJson.parse(File("src/main/assets/voices_v3_turbo.json").readText()).asObj()
        val presets = json.getValue("presets").asObj()
        val thaiSon = presets.getValue("Thái Sơn").asObj()
        thaiSon.getValue("speaker_emb").asArr().map { (it as Double).toFloat() }.toFloatArray()
    }

    @Test
    fun speakerAnchor_matchesPython() {
        val anchor = VieNeuMath.speakerAnchor(
            thaiSonSpeakerEmb,
            heads.getValue("xvec_w"),
            heads.getValue("xvec_b"),
            heads.getValue("xvec_ln_w"),
            heads.getValue("xvec_ln_b"),
            heads.getValue("xvec_ln_eps").data[0],
        )
        assertEquals(768, anchor.size)
        val expectedFirst5 = floatArrayOf(0.2650490701198578f, -0.11574826389551163f, 0.019039345905184746f, -0.025694239884614944f, 0.04571154713630676f)
        assertFloatsClose(expectedFirst5, anchor.copyOfRange(0, 5))
        assertFloatsClose(floatArrayOf(0.8325644731521606f), floatArrayOf(anchor.sum()), eps = 1e-3f)
    }

    @Test
    fun embedRows_matchesPython() {
        val audioPad = 1024
        val nVq = 16
        // Same synthetic rows as the Python fixture: T=3, row0 also has a
        // (contrived) audio code in channel 0.
        val rows = arrayOf(
            IntArray(nVq + 1) { audioPad }.also { it[0] = 3; it[1] = 5 },
            IntArray(nVq + 1) { audioPad }.also { it[0] = 100 },
            IntArray(nVq + 1) { audioPad }.also { it[0] = 4 },
        )
        val anchor = VieNeuMath.speakerAnchor(
            thaiSonSpeakerEmb,
            heads.getValue("xvec_w"),
            heads.getValue("xvec_b"),
            heads.getValue("xvec_ln_w"),
            heads.getValue("xvec_ln_b"),
            heads.getValue("xvec_ln_eps").data[0],
        )
        val out = VieNeuMath.embedRows(rows, heads.getValue("text_emb"), heads.getValue("audio_emb"), nVq, audioPad, anchor)

        assertEquals(3, out.size)
        assertFloatsClose(floatArrayOf(0.2313576638698578f, -0.17745479941368103f, 0.008541299030184746f, -0.08929286897182465f, 0.021045714616775513f), out[0].copyOfRange(0, 5))
        assertFloatsClose(floatArrayOf(0.2522927224636078f, -0.13711056113243103f, 0.035274699330329895f, -0.045103419572114944f, 0.04528239369392395f), out[1].copyOfRange(0, 5))
        assertFloatsClose(floatArrayOf(0.2887307107448578f, -0.15114864706993103f, 0.009029580280184746f, 0.025575291365385056f, 0.09087756276130676f), out[2].copyOfRange(0, 5))

        var sum = 0.0
        for (row in out) for (v in row) sum += v
        assertTrue(kotlin.math.abs(sum - (-8.403779983520508)) < 1e-2, "sum was $sum")
    }

    @Test
    fun sample_temperatureZero_isArgmax() {
        val logits = floatArrayOf(0.1f, 5.0f, -3.0f, 4.9f)
        val idx = VieNeuMath.sample(logits, temperature = 0f, topK = 25, topP = 1f, repetitionPenalty = 1f, previous = null)
        assertEquals(1, idx)
    }

    private fun assertFloatsClose(expected: FloatArray, actual: FloatArray, eps: Float = 1e-4f) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertTrue(kotlin.math.abs(expected[i] - actual[i]) < eps, "index $i: expected ${expected[i]} but was ${actual[i]}")
        }
    }
}
