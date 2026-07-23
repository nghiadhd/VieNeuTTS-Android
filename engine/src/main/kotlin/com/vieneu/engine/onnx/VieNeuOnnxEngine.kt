package com.vieneu.engine.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.vieneu.engine.model.VieNeuConfig
import java.nio.FloatBuffer

/**
 * Kotlin port of `OnnxV3LiteEngine`'s ONNX orchestration (prefill so far;
 * decode_step/acoustic/codec land in follow-up commits). Framework-wise this
 * is plain `ai.onnxruntime.*` — the same API is provided by both
 * `onnxruntime-android` (what ships in the app) and the desktop
 * `onnxruntime` artifact (test-only), so this class is exercised by JVM unit
 * tests on host before ever running on a device.
 */
class VieNeuOnnxEngine(
    private val env: OrtEnvironment,
    private val sessPrefill: OrtSession,
    private val config: VieNeuConfig,
) : AutoCloseable {

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
    }
}
