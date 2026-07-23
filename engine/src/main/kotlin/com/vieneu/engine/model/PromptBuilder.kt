package com.vieneu.engine.model

import com.vieneu.engine.tokenizer.BpeTokenizer

/**
 * Mirrors `OnnxV3LiteEngine._build_rows`: turns a phonemized string plus an
 * optional reference-voice code matrix into the `(T, nVq+1)` row layout fed
 * to [VieNeuMath.embedRows]. Column 0 is a text/control token id; columns
 * `1..nVq` are per-channel audio-code ids (`audioPad` where none apply).
 */
object PromptBuilder {
    fun buildRows(
        phonemes: String,
        tokenizer: BpeTokenizer,
        styleId: Int,
        refCodes: Array<IntArray>?, // (Tref, nVq), or null for no in-context reference
        config: VieNeuConfig,
    ): Array<IntArray> {
        val phoneIds = tokenizer.encode(phonemes)
        val textIds = buildList {
            add(styleId)
            add(config.textPromptStart)
            addAll(phoneIds)
            add(config.textPromptEnd)
        }
        val textRows = Array(textIds.size) { t ->
            IntArray(config.nVq + 1) { config.audioPad }.also { it[0] = textIds[t] }
        }
        if (refCodes == null) return textRows

        val refRows = Array(refCodes.size) { r ->
            IntArray(config.nVq + 1) { config.audioPad }.also { row ->
                row[0] = config.audioRefSlot
                for (ch in 0 until config.nVq) row[ch + 1] = refCodes[r][ch]
            }
        }
        return textRows + refRows
    }
}
