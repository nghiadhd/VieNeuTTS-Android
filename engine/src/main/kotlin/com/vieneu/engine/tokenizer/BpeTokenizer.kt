package com.vieneu.engine.tokenizer

import java.text.Normalizer

/**
 * GPT-2-style byte-level BPE tokenizer, matching the `tokenizers` library's
 * `Tokenizer.encode(text, add_special_tokens=False).ids` for VieNeu-TTS's
 * `tokenizer.json` (NFC normalizer, `Split` + `ByteLevel` pre-tokenizer,
 * `BPE` model). Only encoding is implemented — VieNeu-TTS never decodes
 * token ids back to text.
 */
class BpeTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val bpeRanks: Map<Pair<String, String>, Int>,
) {
    // Mirrors tokenizer.json's pre_tokenizer.pretokenizers[0].pattern (Split, Isolated).
    private val preTokenizeRegex = Regex(
        "(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}| ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+",
    )

    fun encode(text: String): List<Int> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        val ids = mutableListOf<Int>()
        for (match in preTokenizeRegex.findAll(normalized)) {
            val piece = Gpt2ByteLevel.encode(match.value)
            for (token in bpe(piece)) {
                ids.add(vocab[token] ?: vocab.getValue("<|unk|>"))
            }
        }
        return ids
    }

    /** Standard GPT-2 `bpe()`: repeatedly merge the lowest-rank adjacent pair. */
    private fun bpe(piece: String): List<String> {
        if (piece.length <= 1) return listOf(piece)
        var word = piece.map { it.toString() }
        while (word.size > 1) {
            var bestPair: Pair<String, String>? = null
            var bestRank = Int.MAX_VALUE
            for (idx in 0 until word.size - 1) {
                val pair = word[idx] to word[idx + 1]
                val rank = bpeRanks[pair] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    bestPair = pair
                }
            }
            val (first, second) = bestPair ?: break
            val merged = mutableListOf<String>()
            var i = 0
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                    merged.add(first + second)
                    i += 2
                } else {
                    merged.add(word[i])
                    i += 1
                }
            }
            word = merged
        }
        return word
    }

    companion object {
        fun fromJson(json: String): BpeTokenizer {
            val root = MiniJson.parse(json).asObj()
            val model = root.getValue("model").asObj()
            val vocab = model.getValue("vocab").asObj().mapValues { it.value.asNum() }
            val merges = model.getValue("merges").asArr().mapIndexed { rank, entry ->
                val pair = entry.asArr()
                (pair[0].asStr() to pair[1].asStr()) to rank
            }.toMap()
            return BpeTokenizer(vocab, merges)
        }
    }
}
