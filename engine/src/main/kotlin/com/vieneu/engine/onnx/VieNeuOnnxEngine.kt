package com.vieneu.engine.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.vieneu.engine.model.VieNeuConfig
import com.vieneu.engine.model.VieNeuMath
import com.vieneu.engine.npy.NpyArray
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.random.Random

/**
 * Kotlin port of `OnnxV3LiteEngine`'s ONNX orchestration (prefill + the
 * per-frame acoustic loop so far; the outer backbone decode_step loop and
 * codec decode land in follow-up commits). Framework-wise this is plain
 * `ai.onnxruntime.*` — the same API is provided by both `onnxruntime-android`
 * (what ships in the app) and the desktop `onnxruntime` artifact (test-only),
 * so this class is exercised by JVM unit tests on host before ever running
 * on a device.
 */
class VieNeuOnnxEngine(
    private val env: OrtEnvironment,
    private val sessPrefill: OrtSession,
    private val sessDecodeStep: OrtSession,
    private val sessAcoustic: OrtSession,
    private val textEmb: NpyArray, // (Vt, H)
    private val audioEmb: NpyArray, // (nVq, Va, H)
    private val config: VieNeuConfig,
) : AutoCloseable {
    private val vocabAudio = audioEmb.shape[1]
    private val vocabText = textEmb.shape[0]

    /** `hiddenLast` = hidden[:, -1] (H,); `pastK`/`pastV` = one tensor per backbone layer, still owned by the caller. */
    class PrefillResult(val hiddenLast: FloatArray, val pastK: List<OnnxTensor>, val pastV: List<OnnxTensor>) : AutoCloseable {
        override fun close() {
            pastK.forEach { it.close() }
            pastV.forEach { it.close() }
        }
    }

    /** Mirrors the prefill half of `infer()`: one forward pass over the whole prompt. */
    fun prefill(promptEmbeds: Array<FloatArray>): PrefillResult {
        val t = promptEmbeds.size
        val h = config.hidden
        val flat = FloatArray(t * h)
        for (i in 0 until t) System.arraycopy(promptEmbeds[i], 0, flat, i * h, h)

        OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, t.toLong(), h.toLong())).use { input ->
            sessPrefill.run(mapOf("inputs_embeds" to input)).use { result ->
                val hidden = (result.get("hidden").get() as OnnxTensor).floatBuffer
                val hiddenLast = FloatArray(h)
                val lastRowOffset = (t - 1) * h
                for (d in 0 until h) hiddenLast[d] = hidden.get(lastRowOffset + d)

                val pastK = (0 until config.numHiddenLayers).map { i ->
                    detachTensor(env, result.get("present_k_$i").get() as OnnxTensor)
                }
                val pastV = (0 until config.numHiddenLayers).map { i ->
                    detachTensor(env, result.get("present_v_$i").get() as OnnxTensor)
                }
                return PrefillResult(hiddenLast, pastK, pastV)
            }
        }
    }

    /**
     * Mirrors the full frame-generation loop in `infer()`: prefill once,
     * then alternate `acousticFrame` (sample one frame's codes from the
     * current backbone hidden state) with `decodeStep` (feed those codes
     * back in to get the next backbone hidden state), until EOS or
     * [maxNewFrames]. Returns one `IntArray` (length [VieNeuConfig.nVq]) per
     * generated frame.
     */
    fun generateFrames(
        promptEmbeds: Array<FloatArray>,
        anchor: FloatArray?,
        maxNewFrames: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        repetitionPenalty: Float,
        random: Random = Random.Default,
    ): List<IntArray> {
        val hist = if (repetitionPenalty != 1.0f) List(config.nVq) { mutableSetOf<Int>() } else null
        val tPrompt = promptEmbeds.size
        val frames = mutableListOf<IntArray>()

        // NOT `prefill(...).use{}`: ownership of pastK/pastV transfers into this
        // loop and gets closed manually below as each iteration replaces them —
        // PrefillResult.close() would then double-close the already-replaced ones.
        val pre = prefill(promptEmbeds)
        var h = pre.hiddenLast
        var pastK = pre.pastK
        var pastV = pre.pastV
        try {
            for (t in 0 until maxNewFrames) {
                val frame = acousticFrame(h, temperature, topK, topP, repetitionPenalty, hist, random)
                frames.add(frame.codes)
                if (frame.eos) break

                val slotRow = IntArray(config.nVq + 1) { config.audioPad }
                slotRow[0] = config.speechGenerationStart
                for (ch in 0 until config.nVq) slotRow[ch + 1] = frame.codes[ch]
                val slotEmbed = VieNeuMath.embedRows(arrayOf(slotRow), textEmb, audioEmb, config.nVq, config.audioPad, anchor)[0]

                val (nextH, newPastK, newPastV) = decodeStep(slotEmbed, position = (tPrompt + t).toLong(), pastK = pastK, pastV = pastV)
                pastK.forEach { it.close() }; pastV.forEach { it.close() }
                pastK = newPastK; pastV = newPastV
                h = nextH
            }
        } finally {
            pastK.forEach { it.close() }
            pastV.forEach { it.close() }
        }
        return frames
    }

    /** One `sess_dec.run(...)` call: the backbone's single-token continuation step. */
    private fun decodeStep(
        slotEmbed: FloatArray,
        position: Long,
        pastK: List<OnnxTensor>,
        pastV: List<OnnxTensor>,
    ): Triple<FloatArray, List<OnnxTensor>, List<OnnxTensor>> {
        val h = config.hidden
        OnnxTensor.createTensor(env, FloatBuffer.wrap(slotEmbed), longArrayOf(1, 1, h.toLong())).use { inputsEmbeds ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(position)), longArrayOf(1, 1)).use { positionIds ->
                val inputs = mutableMapOf<String, OnnxTensor>("inputs_embeds" to inputsEmbeds, "position_ids" to positionIds)
                for (i in pastK.indices) inputs["past_k_$i"] = pastK[i]
                for (i in pastV.indices) inputs["past_v_$i"] = pastV[i]
                sessDecodeStep.run(inputs).use { result ->
                    val nextH = toArray((result.get("hidden").get() as OnnxTensor).floatBuffer)
                    val newPastK = (0 until config.numHiddenLayers).map { i -> detachTensor(env, result.get("present_k_$i").get() as OnnxTensor) }
                    val newPastV = (0 until config.numHiddenLayers).map { i -> detachTensor(env, result.get("present_v_$i").get() as OnnxTensor) }
                    return Triple(nextH, newPastK, newPastV)
                }
            }
        }
    }

    class AcousticFrameResult(val codes: IntArray, val eos: Boolean)

    /**
     * Mirrors `_acoustic_frame`: samples one full frame (one code per
     * channel, [config.nVq] of them) from the backbone's hidden state [h]
     * for this timestep, driving the small (`L_loc`-layer, reset every
     * frame) acoustic decoder token-by-token. [hist] is the optional
     * per-channel repetition-penalty history (`null` disables it, matching
     * `repetition_penalty == 1.0` in Python).
     */
    fun acousticFrame(
        h: FloatArray,
        temperature: Float,
        topK: Int,
        topP: Float,
        repetitionPenalty: Float,
        hist: List<MutableSet<Int>>?,
        random: Random = Random.Default,
    ): AcousticFrameResult {
        val hidden = config.hidden
        val txt = FloatArray(hidden)
        val sgsOff = config.speechGenerationStart * hidden
        for (d in 0 until hidden) txt[d] = textEmb.data[sgsOff + d]

        val nHLoc = config.localNumAttentionHeads
        val hdLoc = hidden / nHLoc

        var pk = emptyPast(nHLoc, hdLoc)
        var pv = emptyPast(nHLoc, hdLoc)

        // First call: 2 tokens (backbone hidden, then <|SPEECH_GENERATION_START|>'s text embedding).
        val tok2 = FloatArray(2 * hidden)
        System.arraycopy(h, 0, tok2, 0, hidden)
        System.arraycopy(txt, 0, tok2, hidden, hidden)
        var (frameHidden, newPk, newPv) = runAcousticStep(tok2, seqLen = 2, positions = longArrayOf(0, 1), pastK = pk, pastV = pv)
        pk.forEach { it.close() }; pv.forEach { it.close() }
        pk = newPk; pv = newPv

        val slot0 = frameHidden.copyOfRange(0, hidden) // frameHidden[0, 0]
        val pos1 = frameHidden.copyOfRange(hidden, 2 * hidden) // frameHidden[0, 1]

        val codes = IntArray(config.nVq)
        codes[0] = sampleChannel(0, pos1, temperature, topK, topP, repetitionPenalty, hist, random)

        for (ch in 1 until config.nVq) {
            val emb = FloatArray(hidden)
            val prevOff = (ch - 1) * vocabAudio * hidden + codes[ch - 1] * hidden
            for (d in 0 until hidden) emb[d] = audioEmb.data[prevOff + d]

            val step = runAcousticStep(emb, seqLen = 1, positions = longArrayOf((ch + 1).toLong()), pastK = pk, pastV = pv)
            pk.forEach { it.close() }; pv.forEach { it.close() }
            pk = step.second; pv = step.third

            codes[ch] = sampleChannel(ch, step.first, temperature, topK, topP, repetitionPenalty, hist, random)
        }
        pk.forEach { it.close() }; pv.forEach { it.close() }

        val textLogits = VieNeuMath.projectLogits(slot0, textEmb, channelOffset = 0, vocabSize = vocabText)
        var eosArgmax = 0
        for (i in textLogits.indices) if (textLogits[i] > textLogits[eosArgmax]) eosArgmax = i
        val eos = eosArgmax == config.speechGenerationEnd

        return AcousticFrameResult(codes, eos)
    }

    private fun sampleChannel(
        channel: Int,
        hiddenVec: FloatArray,
        temperature: Float,
        topK: Int,
        topP: Float,
        repetitionPenalty: Float,
        hist: List<MutableSet<Int>>?,
        random: Random,
    ): Int {
        val logits = VieNeuMath.projectLogits(hiddenVec, audioEmb, channelOffset = channel * vocabAudio * config.hidden, vocabSize = vocabAudio)
        val previous = hist?.get(channel)
        val code = VieNeuMath.sample(logits, temperature, topK, topP, repetitionPenalty, previous, random)
        previous?.add(code)
        return code
    }

    private fun emptyPast(nHLoc: Int, hdLoc: Int): List<OnnxTensor> =
        (0 until config.localNumHiddenLayers).map {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(0)), longArrayOf(1, nHLoc.toLong(), 0, hdLoc.toLong()))
        }

    /** One `sess_ac.run(...)` call. Returns (flattened hidden output, new pastK tensors, new pastV tensors). */
    private fun runAcousticStep(
        tokenEmbFlat: FloatArray,
        seqLen: Int,
        positions: LongArray,
        pastK: List<OnnxTensor>,
        pastV: List<OnnxTensor>,
    ): Triple<FloatArray, List<OnnxTensor>, List<OnnxTensor>> {
        val hidden = config.hidden
        OnnxTensor.createTensor(env, FloatBuffer.wrap(tokenEmbFlat), longArrayOf(1, seqLen.toLong(), hidden.toLong())).use { tokenEmb ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(positions), longArrayOf(1, positions.size.toLong())).use { positionIds ->
                val inputs = mutableMapOf<String, OnnxTensor>("token_emb" to tokenEmb, "position_ids" to positionIds)
                for (i in pastK.indices) inputs["past_k_$i"] = pastK[i]
                for (i in pastV.indices) inputs["past_v_$i"] = pastV[i]
                sessAcoustic.run(inputs).use { result ->
                    val hiddenOut = toArray((result.get("hidden").get() as OnnxTensor).floatBuffer)
                    val newPk = (0 until config.localNumHiddenLayers).map { i -> detachTensor(env, result.get("present_k_$i").get() as OnnxTensor) }
                    val newPv = (0 until config.localNumHiddenLayers).map { i -> detachTensor(env, result.get("present_v_$i").get() as OnnxTensor) }
                    return Triple(hiddenOut, newPk, newPv)
                }
            }
        }
    }

    private fun toArray(buf: java.nio.FloatBuffer): FloatArray {
        val dup = buf.duplicate()
        val arr = FloatArray(dup.remaining())
        dup.get(arr)
        return arr
    }

    /**
     * `OrtSession.Result` closes its tensors when the `use{}` block exits, so
     * KV-cache outputs we need to keep across loop iterations must be copied
     * into independently-owned tensors first.
     */
    private fun detachTensor(env: OrtEnvironment, src: OnnxTensor): OnnxTensor {
        val buf = src.floatBuffer
        val copy = FloatArray(buf.remaining())
        buf.get(copy)
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(copy), src.info.shape)
    }

    override fun close() {
        sessPrefill.close()
        sessDecodeStep.close()
        sessAcoustic.close()
    }
}
