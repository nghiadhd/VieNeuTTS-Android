package com.vieneu.engine.onnx

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.vieneu.engine.model.PromptBuilder
import com.vieneu.engine.model.VieNeuConfig
import com.vieneu.engine.model.VieNeuMath
import com.vieneu.engine.npy.NpyReader
import com.vieneu.engine.tokenizer.BpeTokenizer
import com.vieneu.engine.tokenizer.MiniJson
import com.vieneu.engine.tokenizer.asArr
import com.vieneu.engine.tokenizer.asObj
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Expected values are the real `sess_pre.run(...)` output from Python on the
 * identical prompt (see design spec's shift-left notes) — end-to-end proof
 * that tokenizer + embedding math + prompt rows + the actual ONNX prefill
 * graph agree with VieNeu-TTS's own engine, entirely on host (desktop
 * onnxruntime), before this ever runs on a device.
 */
class VieNeuOnnxEnginePrefillTest {
    private val assetsDir = File("src/main/assets/onnx_update")

    @Test
    fun prefill_matchesPythonOnnxRuntimeLite() {
        val config = VieNeuConfig.fromJson(File(assetsDir, "config.json").readText())
        val tokenizer = BpeTokenizer.fromJson(File(assetsDir, "tokenizer.json").readText())
        val heads = File(assetsDir, "vieneu_v3_heads.npz").inputStream().use { NpyReader.parseNpz(it) }

        val voicesJson = MiniJson.parse(File("src/main/assets/voices_v3_turbo.json").readText()).asObj()
        val thaiSon = voicesJson.getValue("presets").asObj().getValue("Thái Sơn").asObj()
        val speakerEmb = thaiSon.getValue("speaker_emb").asArr().map { (it as Double).toFloat() }.toFloatArray()
        val codesRaw = thaiSon.getValue("codes").asArr()
        val refCodes = Array(codesRaw.size) { r -> codesRaw[r].asArr().map { (it as Double).toInt() }.toIntArray() }

        val phonemes = "zˈaːɜ ˈɛɜt̪ pˈe nˈam tʃˈam hˈom nˈaj lˌaː2 bˈoɜn ŋˈi2n hˈaːj tʃˈam fˈəɪ4 nˈam ɗˈiɛ4m."
        val styleId = config.resolveStyleId("doc_truyen")
        val rows = PromptBuilder.buildRows(phonemes, tokenizer, styleId, refCodes, config)
        assertEquals(126, rows.size)

        val anchor = VieNeuMath.speakerAnchor(
            speakerEmb, heads.getValue("xvec_w"), heads.getValue("xvec_b"),
            heads.getValue("xvec_ln_w"), heads.getValue("xvec_ln_b"), heads.getValue("xvec_ln_eps").data[0],
        )
        val promptEmbeds = VieNeuMath.embedRows(rows, heads.getValue("text_emb"), heads.getValue("audio_emb"), config.nVq, config.audioPad, anchor)

        val env = OrtEnvironment.getEnvironment()
        val sessPrefill = env.createSession(File(assetsDir, "vieneu_prefill.onnx").path, OrtSession.SessionOptions())
        val sessDecodeStep = env.createSession(File(assetsDir, "vieneu_decode_step.onnx").path, OrtSession.SessionOptions())
        val sessAcoustic = env.createSession(File(assetsDir, "vieneu_acoustic_cached.onnx").path, OrtSession.SessionOptions())
        val sessCodecDecode = env.createSession(File("src/main/assets/moss_audio_tokenizer_decode_full.onnx").path, OrtSession.SessionOptions())
        VieNeuOnnxEngine(env, sessPrefill, sessDecodeStep, sessAcoustic, sessCodecDecode, heads.getValue("text_emb"), heads.getValue("audio_emb"), config).use { engine ->
            engine.prefill(promptEmbeds).use { result ->
                assertEquals(768, result.hiddenLast.size)
                val expectedFirst5 = floatArrayOf(0.035474058240652084f, 0.027332818135619164f, -0.0016091817524284124f, -0.048954445868730545f, 0.022672895342111588f)
                assertFloatsClose(expectedFirst5, result.hiddenLast.copyOfRange(0, 5))
                assertFloatsClose(floatArrayOf(-2.827559471130371f), floatArrayOf(result.hiddenLast.sum()), eps = 1e-2f)

                assertEquals(12, result.pastK.size)
                assertEquals(12, result.pastV.size)

                val pastK0 = toArray(result.pastK[0].floatBuffer.duplicate())
                assertFloatsClose(floatArrayOf(0.5203028917312622f, 0.41303420066833496f, 1.0183535814285278f), pastK0.copyOfRange(0, 3))
                assertTrue(kotlin.math.abs(pastK0.sum() - 106.83627319335938f) < 1e-1f, "past_k[0] sum was ${pastK0.sum()}")

                val pastV11 = toArray(result.pastV[11].floatBuffer.duplicate())
                assertFloatsClose(floatArrayOf(0.15098455548286438f, -0.709679126739502f, -0.25733816623687744f), pastV11.copyOfRange(0, 3))
                assertTrue(kotlin.math.abs(pastV11.sum() - (-466.5409240722656f)) < 1e-0f, "past_v[11] sum was ${pastV11.sum()}")
            }
        }
    }

    private fun toArray(buf: java.nio.FloatBuffer): FloatArray {
        val arr = FloatArray(buf.remaining())
        buf.get(arr)
        return arr
    }

    private fun assertFloatsClose(expected: FloatArray, actual: FloatArray, eps: Float = 1e-4f) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertTrue(kotlin.math.abs(expected[i] - actual[i]) < eps, "index $i: expected ${expected[i]} but was ${actual[i]}")
        }
    }
}
