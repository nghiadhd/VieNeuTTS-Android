package com.vieneu.engine

import uniffi.sea_g2p_android.SeaG2p

/**
 * Kotlin port of VieNeu-TTS's `phonemize_text_with_emotions` (Python:
 * `vieneu_utils/phonemize_text.py`) — the experimental inline emotion-cue
 * feature the model's README documents: `[cười]`, `[thở dài]`,
 * `[hắng giọng]` (or their English/no-diacritics spellings, or an already
 * -resolved `<|emotion_k|>`) get left as the matching `<|emotion_k|>`
 * control token in the phoneme stream instead of being spelled out as
 * ordinary text — so the transformer sees the same emotion signal the
 * checkpoint was trained on.
 *
 * Needs [BpeTokenizer]'s added-token handling (see its KDoc) to correctly
 * tokenize the `<|emotion_k|>` markers this produces — without that, they'd
 * be BPE-encoded as ordinary punctuation/letters instead of the single
 * control-token id the model expects.
 */
object EmotionPhonemizer {
    private val TAG_TO_K = mapOf(
        "chuckle" to 1, "cười" to 1, "cuoi" to 1,
        "sigh" to 2, "thở dài" to 2, "tho dai" to 2,
        "clear throat" to 3, "hắng giọng" to 3, "hang giong" to 3,
    )
    private val SPLIT_RE = Regex("(\\[[^\\]]+]|<\\|emotion_\\d+\\|>)")
    private val ATTACHING_PUNCT = ".,!?;:…)]}\"'’”".toSet()

    // internal, not private: lets a JVM host test exercise the tag-detection/
    // splitting logic without the real (Android-only) SeaG2p JNI binding.
    internal fun tagToken(tag: String): String? {
        val t = tag.trim()
        if (t.startsWith("<|")) return t
        val inner = t.substring(1, t.length - 1).trim().lowercase()
        val k = TAG_TO_K[inner] ?: return null
        return "<|emotion_$k|>"
    }

    /** Mirrors `phonemize_text_with_emotions`: normalize+phonemize, preserving `[tag]`s as `<|emotion_k|>`. */
    fun phonemize(g2p: SeaG2p, text: String): String {
        if ("[" !in text && "<|emotion_" !in text) return g2p.run(text, true)

        var out = ""
        // Regex.split with a capturing group, like Python's re.split: odd
        // indices in the resulting alternating [text, tag, text, tag, ...]
        // sequence are the captured tags.
        val parts = splitKeepingDelimiters(text)
        for ((i, part) in parts.withIndex()) {
            val token = if (i % 2 == 1) tagToken(part) else null
            if (token != null) {
                out = if (out.isEmpty()) token else "$out $token"
                continue
            }
            val ph = if (part.isNotEmpty() && part.isNotBlank()) g2p.run(part, false) else ""
            if (ph.isEmpty()) continue
            out = when {
                out.isEmpty() -> ph
                ph[0] in ATTACHING_PUNCT -> out + ph
                else -> "$out $ph"
            }
        }
        return g2p.puncNormOnly(out)
    }

    internal fun splitKeepingDelimiters(text: String): List<String> {
        val result = mutableListOf<String>()
        var last = 0
        for (m in SPLIT_RE.findAll(text)) {
            result.add(text.substring(last, m.range.first))
            result.add(m.value)
            last = m.range.last + 1
        }
        result.add(text.substring(last))
        return result
    }
}
