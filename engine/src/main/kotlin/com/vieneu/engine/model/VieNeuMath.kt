package com.vieneu.engine.model

import com.vieneu.engine.npy.NpyArray
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Numpy-free port of the embedding/anchor/sampling math in
 * `onnx_runtime_lite.py` (`_embed_rows`, `_speaker_anchor`, `_sample`).
 * Pure functions over [FloatArray]/[NpyArray] — no ONNX Runtime or Android
 * dependency, so this is unit-testable on the JVM against real numpy output.
 */
object VieNeuMath {

    /** `_speaker_anchor`: 192-d x-vector -> (H,) anchor (Linear + LayerNorm). */
    fun speakerAnchor(
        speakerEmb: FloatArray,
        xvecW: NpyArray, // (H, spkDim)
        xvecB: NpyArray, // (H,)
        xvecLnW: NpyArray, // (H,)
        xvecLnB: NpyArray, // (H,)
        xvecLnEps: Float,
    ): FloatArray {
        val h = xvecW.shape[0]
        val spkDim = xvecW.shape[1]
        require(speakerEmb.size == spkDim)
        val v = FloatArray(h)
        for (i in 0 until h) {
            var sum = 0f
            val rowOff = i * spkDim
            for (j in 0 until spkDim) sum += speakerEmb[j] * xvecW.data[rowOff + j]
            v[i] = sum + xvecB.data[i]
        }
        var mean = 0f
        for (x in v) mean += x
        mean /= h
        var variance = 0f
        for (x in v) variance += (x - mean) * (x - mean)
        variance /= h
        val denom = sqrt(variance + xvecLnEps)
        val out = FloatArray(h)
        for (i in 0 until h) out[i] = (v[i] - mean) / denom * xvecLnW.data[i] + xvecLnB.data[i]
        return out
    }

    /**
     * `_embed_rows`: `rows[t][0]` is a text/control token id (into `textEmb`);
     * `rows[t][1..nVq]` are audio-code ids per channel (`audioPad` = "no code
     * in this channel"). Sums the text embedding with every valid channel's
     * embedding, then adds the speaker [anchor] to every row if present.
     * Returns `(T, H)` — the batch dim of 1 in the Python code is the
     * caller's concern, not this function's.
     */
    fun embedRows(
        rows: Array<IntArray>, // (T, nVq+1)
        textEmb: NpyArray, // (Vt, H)
        audioEmb: NpyArray, // (nVq, Va, H)
        nVq: Int,
        audioPad: Int,
        anchor: FloatArray?,
    ): Array<FloatArray> {
        val h = textEmb.shape[1]
        val va = audioEmb.shape[1]
        return Array(rows.size) { t ->
            val row = rows[t]
            val out = FloatArray(h)
            val textId = row[0]
            val textOff = textId * h
            for (d in 0 until h) out[d] = textEmb.data[textOff + d]
            for (ch in 0 until nVq) {
                val id = row[ch + 1]
                if (id != audioPad) {
                    val off = (ch * va + id) * h
                    for (d in 0 until h) out[d] += audioEmb.data[off + d]
                }
            }
            if (anchor != null) {
                for (d in 0 until h) out[d] += anchor[d]
            }
            out
        }
    }

    /**
     * `_sample`: repetition penalty -> temperature -> top-k -> top-p nucleus
     * -> multinomial draw (or, when `temperature <= 0`, plain argmax). The
     * repetition-penalty/temperature/top-k/argmax paths are deterministic and
     * checked bit-for-bit against Python in tests; the multinomial draw
     * itself is inherently stochastic (RNG streams aren't expected to match
     * Python's), only its *distribution* is.
     */
    fun sample(
        logitsIn: FloatArray,
        temperature: Float,
        topK: Int,
        topP: Float,
        repetitionPenalty: Float,
        previous: Set<Int>?,
        random: Random = Random.Default,
    ): Int {
        val logits = logitsIn.copyOf()
        if (repetitionPenalty != 1.0f && !previous.isNullOrEmpty()) {
            for (idx in previous) {
                val sel = logits[idx]
                logits[idx] = if (sel < 0f) sel * repetitionPenalty else sel / repetitionPenalty
            }
        }
        if (temperature <= 0f) {
            var best = 0
            for (i in logits.indices) if (logits[i] > logits[best]) best = i
            return best
        }
        for (i in logits.indices) logits[i] = logits[i] / temperature

        val v = logits.size
        val candidates: IntArray = if (topK in 1 until v) {
            logits.indices.sortedByDescending { logits[it] }.take(topK).toIntArray()
        } else {
            IntArray(v) { it }
        }
        // Sort candidates by descending logit (mirrors argsort(...)[::-1] over the candidate set).
        val order = candidates.sortedByDescending { logits[it] }.toIntArray()
        val probs = softmax(FloatArray(order.size) { logits[order[it]] })

        val kept: FloatArray
        if (topP < 1.0f) {
            var cumBefore = 0f
            val mask = BooleanArray(probs.size)
            for (i in probs.indices) {
                mask[i] = cumBefore < topP
                cumBefore += probs[i]
            }
            val filtered = FloatArray(probs.size) { if (mask[it]) probs[it] else 0f }
            val sum = filtered.sum()
            kept = FloatArray(filtered.size) { filtered[it] / sum }
        } else {
            kept = probs
        }

        val r = random.nextFloat()
        var acc = 0f
        for (i in kept.indices) {
            acc += kept[i]
            if (r < acc) return order[i]
        }
        return order[kept.size - 1]
    }

    /**
     * Weight-tied output projection: `vec (H,) @ table[channel].T -> (Vrow,)`,
     * mirrors `vec @ self.audio_emb[ch].T` / `slot0 @ self.text_emb.T`. `table`
     * is `(nRows, H)` for the text head or `(nChannels, Va, H)` for an audio
     * channel head — pass `channelOffset = 0` for the former.
     */
    fun projectLogits(vec: FloatArray, table: NpyArray, channelOffset: Int, vocabSize: Int): FloatArray {
        val h = vec.size
        val out = FloatArray(vocabSize)
        for (v in 0 until vocabSize) {
            var sum = 0f
            val rowOff = channelOffset + v * h
            for (d in 0 until h) sum += vec[d] * table.data[rowOff + d]
            out[v] = sum
        }
        return out
    }

    fun softmax(x: FloatArray): FloatArray {
        var max = Float.NEGATIVE_INFINITY
        for (v in x) if (v > max) max = v
        val exps = FloatArray(x.size) { exp(x[it] - max) }
        var sum = 0f
        for (v in exps) sum += v
        for (i in exps.indices) exps[i] = exps[i] / sum
        return exps
    }
}
