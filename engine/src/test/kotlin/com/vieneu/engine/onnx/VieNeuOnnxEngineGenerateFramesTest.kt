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
import org.junit.Test

/**
 * Expected frames are the real 5-frame `infer()` loop output from Python
 * (prefill -> 5x [acoustic_frame, decode_step], temperature=0 so
 * deterministic/exactly reproducible) on the same prompt this suite already
 * validates prefill/acoustic_frame against — end-to-end proof that the outer
 * backbone decode_step loop threads the KV-cache and re-embeds each sampled
 * frame identically to onnx_runtime_lite.py's `infer()`, entirely on host.
 */
class VieNeuOnnxEngineGenerateFramesTest {
    private val assetsDir = File("src/main/assets/onnx_update")

    @Test
    fun generateFrames_matchesPythonOnnxRuntimeLite() {
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
        VieNeuOnnxEngine(env, sessPrefill, sessDecodeStep, sessAcoustic, heads.getValue("text_emb"), heads.getValue("audio_emb"), config).use { engine ->
            val frames = engine.generateFrames(
                promptEmbeds = promptEmbeds,
                anchor = anchor,
                maxNewFrames = 5,
                temperature = 0f,
                topK = 25,
                topP = 0.95f,
                repetitionPenalty = 1f,
            )

            val expected = listOf(
                intArrayOf(393, 169, 926, 325, 917, 241, 944, 142, 389, 705, 682, 495, 181, 650, 51, 360),
                intArrayOf(290, 454, 45, 480, 667, 940, 734, 156, 390, 260, 895, 536, 652, 288, 589, 991),
                intArrayOf(366, 424, 605, 740, 763, 295, 633, 22, 802, 358, 676, 18, 365, 229, 166, 866),
                intArrayOf(947, 124, 935, 987, 240, 28, 172, 109, 951, 510, 1, 228, 913, 738, 668, 1004),
                intArrayOf(640, 374, 896, 969, 33, 599, 479, 478, 653, 13, 119, 652, 1015, 547, 816, 264),
            )
            assertEquals(expected.size, frames.size)
            for (i in expected.indices) {
                assertEquals(expected[i].toList(), frames[i].toList(), "frame $i")
            }
        }
    }
}
