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
import kotlin.test.assertFalse
import org.junit.Test

/**
 * Expected `codes`/`eos` are the real `_acoustic_frame(h, temperature=0, ...)`
 * output from Python (argmax sampling — deterministic, so exactly
 * reproducible), fed the same prefill `h_last` this suite already validated
 * against Python in [VieNeuOnnxEnginePrefillTest] (see design spec's
 * shift-left notes).
 */
class VieNeuOnnxEngineAcousticFrameTest {
    private val assetsDir = File("src/main/assets/onnx_update")

    @Test
    fun acousticFrame_matchesPythonOnnxRuntimeLite() {
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
            engine.prefill(promptEmbeds).use { prefillResult ->
                val frame = engine.acousticFrame(
                    h = prefillResult.hiddenLast,
                    temperature = 0f,
                    topK = 25,
                    topP = 0.95f,
                    repetitionPenalty = 1f,
                    hist = null,
                )
                val expectedCodes = intArrayOf(393, 169, 926, 325, 917, 241, 944, 142, 389, 705, 682, 495, 181, 650, 51, 360)
                assertEquals(expectedCodes.toList(), frame.codes.toList())
                assertFalse(frame.eos)
            }
        }
    }
}
