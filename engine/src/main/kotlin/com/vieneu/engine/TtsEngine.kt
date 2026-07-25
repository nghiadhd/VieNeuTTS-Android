package com.vieneu.engine

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.content.Context
import com.vieneu.engine.model.PromptBuilder
import com.vieneu.engine.model.VieNeuConfig
import com.vieneu.engine.model.VieNeuMath
import com.vieneu.engine.npy.NpyArray
import com.vieneu.engine.npy.NpyReader
import com.vieneu.engine.onnx.VieNeuOnnxEngine
import com.vieneu.engine.tokenizer.BpeTokenizer
import com.vieneu.engine.tokenizer.MiniJson
import com.vieneu.engine.tokenizer.asArr
import com.vieneu.engine.tokenizer.asObj
import java.io.File
import uniffi.sea_g2p_android.SeaG2p

/**
 * Public API of the `:engine` module — this is what `:app-sample` (and later
 * the EPUB reader app) depends on. Wires together the two independently
 * validated halves (see docs/superpowers/specs): the Rust/uniffi phonemizer
 * and the [VieNeuOnnxEngine] port of `onnx_runtime_lite.py`.
 */
class TtsEngine private constructor(
    private val g2p: SeaG2p,
    private val onnx: VieNeuOnnxEngine,
    private val config: VieNeuConfig,
    private val tokenizer: BpeTokenizer,
    private val heads: Map<String, NpyArray>,
    private val voices: Map<String, Voice>,
) : AutoCloseable {

    private class Voice(val speakerEmb: FloatArray, val codes: Array<IntArray>, val style: String)

    /**
     * Mirrors `phonemize_text_with_emotions`: normalizes + phonemizes,
     * preserving inline `[cười]`/`[thở dài]`/`[hắng giọng]` cues as the
     * `<|emotion_k|>` control tokens the model was trained on (see
     * [EmotionPhonemizer]). Falls back to plain `SEAPipeline.run` when the
     * text has no such cues.
     */
    fun phonemize(text: String): String = EmotionPhonemizer.phonemize(g2p, text)

    fun listVoices(): List<String> = voices.keys.toList()

    /**
     * Synthesizes [text] with the built-in preset [voice] (see [listVoices]).
     * Returns 48kHz mono float32 PCM in `[-1, 1]`. Sampling defaults match
     * `Vieneu().infer()`'s.
     */
    fun synthesize(
        text: String,
        voice: String,
        maxNewFrames: Int = 300,
        temperature: Float = 0.8f,
        topK: Int = 25,
        topP: Float = 0.95f,
        repetitionPenalty: Float = 1.2f,
    ): FloatArray {
        val v = voices[voice] ?: error("Unknown voice '$voice'. Available: ${voices.keys}")
        val phonemes = phonemize(text)
        val styleId = config.resolveStyleId(v.style)
        val rows = PromptBuilder.buildRows(phonemes, tokenizer, styleId, v.codes, config)
        val anchor = VieNeuMath.speakerAnchor(
            v.speakerEmb, heads.getValue("xvec_w"), heads.getValue("xvec_b"),
            heads.getValue("xvec_ln_w"), heads.getValue("xvec_ln_b"), heads.getValue("xvec_ln_eps").data[0],
        )
        val promptEmbeds = VieNeuMath.embedRows(rows, heads.getValue("text_emb"), heads.getValue("audio_emb"), config.nVq, config.audioPad, anchor)
        val frames = onnx.generateFrames(promptEmbeds, anchor, maxNewFrames, temperature, topK, topP, repetitionPenalty)
        return onnx.decodeCodes(frames)
    }

    override fun close() {
        onnx.close()
    }

    companion object {
        private const val DICT_ASSET = "sea_g2p.bin"
        private const val VOICES_ASSET = "voices_v3_turbo.json"
        private const val ONNX_DIR = "onnx_update"
        private val ONNX_MODEL_FILES = listOf(
            "$ONNX_DIR/vieneu_prefill.onnx",
            "$ONNX_DIR/vieneu_decode_step.onnx",
            "$ONNX_DIR/vieneu_acoustic_cached.onnx",
            "$ONNX_DIR/vieneu_backbone_shared.data",
            "moss_audio_tokenizer_decode_full.onnx",
            "moss_audio_tokenizer_decode_shared.data",
        )

        /**
         * The 14 preset voice names, without paying [create]'s cost (asset
         * copy + loading 4 ONNX sessions) — for UI like a voice picker that
         * just needs the list, not a working engine.
         */
        fun listVoicesLightweight(context: Context): List<String> =
            loadVoices(context).keys.toList()

        /**
         * Copies the bundled model assets out of the read-only APK into
         * app-private storage once (ONNX Runtime and the Rust G2P core both
         * need real filesystem paths — mmap/external-data can't work
         * directly against an APK asset stream), then loads everything.
         */
        fun create(context: Context, intraOpThreads: Int = 2): TtsEngine =
            createPool(context, workerCount = 1, intraOpThreadsPerWorker = intraOpThreads).single()

        /**
         * Creates [workerCount] independent engines that can each call synthesize() concurrently
         * on their own thread — one sentence in flight per worker, which is a real wall-clock win
         * since sentences are fully independent of each other (unlike the sequential decode loop
         * *within* one sentence, which can't be parallelized this way). Read-only shared resources
         * (config/tokenizer/embedding heads/voice presets, and the asset copy-out) are loaded once
         * and reused by every worker; each still gets its own ONNX sessions and G2P instance,
         * neither of which is documented as safe for concurrent calls from multiple threads.
         */
        fun createPool(context: Context, workerCount: Int, intraOpThreadsPerWorker: Int): List<TtsEngine> {
            require(workerCount >= 1) { "workerCount must be >= 1, was $workerCount" }
            val dictFile = copyAssetOnce(context, DICT_ASSET, DICT_ASSET)
            for (rel in ONNX_MODEL_FILES) copyAssetOnce(context, rel, rel)

            val config = VieNeuConfig.fromJson(context.assets.open("$ONNX_DIR/config.json").bufferedReader().readText())
            val tokenizer = BpeTokenizer.fromJson(context.assets.open("$ONNX_DIR/tokenizer.json").bufferedReader().readText())
            val heads = context.assets.open("$ONNX_DIR/vieneu_v3_heads.npz").use { NpyReader.parseNpz(it) }
            val voices = loadVoices(context)
            val textEmb = heads.getValue("text_emb")
            val audioEmb = heads.getValue("audio_emb")
            val modelsDir = File(context.filesDir, ONNX_DIR)
            val codecDecodeFile = File(context.filesDir, "moss_audio_tokenizer_decode_full.onnx")

            // The default CPU arena allocator never shrinks — it keeps every high-water-mark
            // byte reserved for the session's lifetime instead of returning freed tensors to
            // the OS, so RSS only ratchets upward sentence after sentence. Plain malloc/free
            // per tensor is slightly slower per call but actually gives memory back between
            // synthesize() calls, which matters more than raw speed on a low-RAM device.
            val env: OrtEnvironment
            val sessionOptions: () -> OrtSession.SessionOptions
            if (workerCount == 1) {
                // A per-session thread pool (ORT's default) means each of our 4 sessions spins
                // up its own worker threads; on a low-RAM device that's 4x the thread/arena
                // overhead for no benefit, since a single engine only ever runs one of its own
                // sessions at a time. A shared global pool (env-level ThreadingOptions +
                // disablePerSessionThreads() on each session below) cuts that down to one.
                //
                // A/B tested (direct listening comparison, raw synthesized PCM, engine-only —
                // bypassing playback and compression entirely) against ORT's defaults after an
                // audio-noise complaint: both produced identical, clean output. The complaint
                // traced instead to the remote/forwarded emulator's own audio playback path, not
                // this tuning — see ReaderPlayer for that investigation.
                val threadingOptions = OrtEnvironment.ThreadingOptions().apply {
                    setGlobalIntraOpNumThreads(intraOpThreadsPerWorker.coerceAtLeast(1))
                    setGlobalInterOpNumThreads(1)
                    setGlobalSpinControl(false)
                }
                env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING, OrtEnvironment.DEFAULT_NAME, threadingOptions)
                sessionOptions = {
                    OrtSession.SessionOptions().apply {
                        disablePerSessionThreads()
                        setCPUArenaAllocator(false)
                    }
                }
            } else {
                // Multiple workers call synthesize() concurrently from different threads — if
                // they all shared one global pool (the single-worker path above), their Run()
                // calls would serialize behind it, defeating the entire point of running them in
                // parallel. Each worker's sessions get their own dedicated pool instead, sized so
                // total CPU usage across all workers stays roughly workerCount * threadsPerWorker.
                // OrtEnvironment itself is a process-wide singleton (the "env" argument to
                // getEnvironment only configures it on the very first call in the process), so
                // per-worker isolation has to happen at the SessionOptions level, not the env.
                env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING)
                sessionOptions = {
                    OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(intraOpThreadsPerWorker.coerceAtLeast(1))
                        setInterOpNumThreads(1)
                        setCPUArenaAllocator(false)
                    }
                }
            }

            return List(workerCount) {
                val g2p = SeaG2p(dictFile.absolutePath, "vi")
                val sessPrefill = env.createSession(File(modelsDir, "vieneu_prefill.onnx").path, sessionOptions())
                val sessDecodeStep = env.createSession(File(modelsDir, "vieneu_decode_step.onnx").path, sessionOptions())
                val sessAcoustic = env.createSession(File(modelsDir, "vieneu_acoustic_cached.onnx").path, sessionOptions())
                val sessCodecDecode = env.createSession(codecDecodeFile.path, sessionOptions())
                val onnx = VieNeuOnnxEngine(env, sessPrefill, sessDecodeStep, sessAcoustic, sessCodecDecode, textEmb, audioEmb, config)
                TtsEngine(g2p, onnx, config, tokenizer, heads, voices)
            }
        }

        private fun loadVoices(context: Context): Map<String, Voice> {
            val json = MiniJson.parse(context.assets.open(VOICES_ASSET).bufferedReader().readText()).asObj()
            val presets = json.getValue("presets").asObj()
            return presets.mapValues { (_, raw) ->
                val v = raw.asObj()
                val speakerEmb = v.getValue("speaker_emb").asArr().map { (it as Double).toFloat() }.toFloatArray()
                val codesRaw = v.getValue("codes").asArr()
                val codes = Array(codesRaw.size) { r -> codesRaw[r].asArr().map { (it as Double).toInt() }.toIntArray() }
                val style = v["style"] as? String ?: "tu_nhien"
                Voice(speakerEmb, codes, style)
            }
        }

        private fun copyAssetOnce(context: Context, assetPath: String, relDestPath: String): File {
            val dest = File(context.filesDir, relDestPath)
            if (!dest.exists() || dest.length() == 0L) {
                dest.parentFile?.mkdirs()
                context.assets.open(assetPath).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
            return dest
        }
    }
}
