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
import java.io.RandomAccessFile
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Not a correctness assertion (real `temperature` sampling is stochastic —
 * RNG streams aren't expected to match Python's) — a smoke test + artifact:
 * synthesizes a full real sentence end-to-end with the same default sampling
 * VieNeu-TTS's SDK uses, saves a WAV, and sanity-checks it's non-trivial
 * audio. The phoneme string is pre-computed by the real Python `sea_g2p`
 * (see design spec — the G2P/JNI leg is validated separately on-device,
 * since the Android .so can't load into a host JVM) so this exercises
 * everything else: tokenizer, embeddings, prefill, the full generation loop,
 * and codec decode, together, on a realistic input length.
 */
class FullPipelineSmokeTest {
    private val assetsDir = File("src/main/assets/onnx_update")

    @Test
    fun synthesizesRealSentenceEndToEnd() {
        val config = VieNeuConfig.fromJson(File(assetsDir, "config.json").readText())
        val tokenizer = BpeTokenizer.fromJson(File(assetsDir, "tokenizer.json").readText())
        val heads = File(assetsDir, "vieneu_v3_heads.npz").inputStream().use { NpyReader.parseNpz(it) }

        val voicesJson = MiniJson.parse(File("src/main/assets/voices_v3_turbo.json").readText()).asObj()
        val thaiSon = voicesJson.getValue("presets").asObj().getValue("Thái Sơn").asObj()
        val speakerEmb = thaiSon.getValue("speaker_emb").asArr().map { (it as Double).toFloat() }.toFloatArray()
        val codesRaw = thaiSon.getValue("codes").asArr()
        val refCodes = Array(codesRaw.size) { r -> codesRaw[r].asArr().map { (it as Double).toInt() }.toIntArray() }

        // sea_g2p.SEAPipeline("vi").run(..., punc_norm=True) on the opening
        // sentence of data/chapter-0001.txt — same text as the Python
        // reference baseline (data/reference-audio/chapter-0001_excerpt.wav).
        val phonemes = "tʃˈɔŋ mˈaː2n mˈyə mˈuə2 hˈaː6 ɹˈe2n vˈaːŋ sˈəɜm sˈɛɜt̪, mˈo6t̪ tʃˈiɛɜc pˈɔːɹ tʃˈɛ mˈa2w ɗˈɛn tʃˈa6j tʃˈen ɗˈyə2ŋ ˈəː4 vˈu2ŋ kwˈe."
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
            val t0 = System.currentTimeMillis()
            val frames = engine.generateFrames(
                promptEmbeds = promptEmbeds,
                anchor = anchor,
                maxNewFrames = 300,
                temperature = 0.8f,
                topK = 25,
                topP = 0.95f,
                repetitionPenalty = 1.2f,
                random = kotlin.random.Random(42),
            )
            val genMs = System.currentTimeMillis() - t0
            println("Generated ${frames.size} frames in ${genMs}ms")
            assertTrue(frames.isNotEmpty())
            assertTrue(frames.size < 300, "hit the max_new_frames cap without EOS — likely wrong, expected natural stop")

            val audio = engine.decodeCodes(frames)
            val durationS = audio.size / 48000.0
            val rtf = (genMs / 1000.0) / durationS
            println("Audio: ${audio.size} samples, ${"%.2f".format(durationS)}s, RTF=${"%.3f".format(rtf)}")
            assertTrue(audio.size > 48000, "expected at least 1s of audio")

            val outFile = File("/tmp/vieneu_android_smoke.wav")
            writeWav(outFile, audio, sampleRate = 48000)
            println("Saved: ${outFile.absolutePath}")
        }
    }

    /** Minimal 16-bit PCM mono WAV writer — no external deps needed for a host-side smoke artifact. */
    private fun writeWav(file: File, audio: FloatArray, sampleRate: Int) {
        val pcm = ShortArray(audio.size) { i -> (audio[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val dataSize = pcm.size * 2
            val byteRate = sampleRate * 2
            fun writeIntLE(v: Int) { for (i in 0..3) raf.writeByte((v shr (8 * i)) and 0xFF) }
            fun writeShortLE(v: Int) { for (i in 0..1) raf.writeByte((v shr (8 * i)) and 0xFF) }
            raf.writeBytes("RIFF"); writeIntLE(36 + dataSize); raf.writeBytes("WAVE")
            raf.writeBytes("fmt "); writeIntLE(16); writeShortLE(1); writeShortLE(1)
            writeIntLE(sampleRate); writeIntLE(byteRate); writeShortLE(2); writeShortLE(16)
            raf.writeBytes("data"); writeIntLE(dataSize)
            for (s in pcm) writeShortLE(s.toInt())
        }
    }
}
