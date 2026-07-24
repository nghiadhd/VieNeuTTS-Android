package com.vieneu.reader.epub

/**
 * Lightweight, rule-based sentence splitter for Vietnamese (and mixed
 * Vietnamese/English) chapter text. Not linguistically perfect — VieNeu-TTS's
 * own Python chunker isn't either (it chunks by character count, not true
 * sentence boundaries) — good enough to produce the ~150-sentence/chapter
 * granularity the buffer-ahead algorithm operates on.
 *
 * Splits on `. ! ? …` followed by whitespace, keeping the terminating
 * punctuation attached to the sentence it ends. Guards against splitting on
 * a handful of common Vietnamese honorifics/abbreviations (`TS.`, `ThS.`,
 * `GS.`) by checking the word right before the period, and against
 * splitting mid-decimal-number (`4.200,5`) or mid-abbreviation (`v.v.`)
 * since a period is only a boundary when immediately followed by
 * whitespace or the end of the paragraph.
 *
 * A "sentence" this finds can still be arbitrarily long — real chapter text
 * sometimes runs for a whole long paragraph of dialogue with no `.!?…`, and
 * each sentence here becomes one uncapped `TtsEngine.synthesize()` call.
 * [MAX_CHARS] (matching VieNeu-TTS's own Python chunker's `max_chars=256`
 * default in `split_text_into_chunks`) bounds that: an oversized sentence is
 * forced into multiple sub-chunks the same way the Python chunker does —
 * split on minor punctuation (`, ; : - – —`) first, falling back to word
 * boundaries if even a comma-separated piece is still too long.
 */
object SentenceSplitter {
    private val ABBREVIATIONS = setOf("ts", "ths", "gs", "pgs", "vs", "tp", "q", "no")
    private const val MAX_CHARS = 256
    private val MINOR_PUNCT_BOUNDARY = Regex("(?<=[,;:\\-–—])\\s+")

    fun split(text: String): List<String> {
        val paragraphs = text.split(Regex("\\r?\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        val sentences = mutableListOf<String>()
        for (paragraph in paragraphs) {
            for (sentence in splitParagraph(paragraph)) sentences.addAll(capLength(sentence))
        }
        return sentences
    }

    private fun capLength(sentence: String): List<String> {
        if (sentence.length <= MAX_CHARS) return listOf(sentence)

        val chunks = mutableListOf<String>()
        var buffer = ""
        for (part in sentence.split(MINOR_PUNCT_BOUNDARY).map { it.trim() }.filter { it.isNotEmpty() }) {
            if (part.length > MAX_CHARS) {
                if (buffer.isNotEmpty()) { chunks.add(buffer); buffer = "" }
                chunks.addAll(splitByWords(part))
            } else if (buffer.isNotEmpty() && buffer.length + 1 + part.length > MAX_CHARS) {
                chunks.add(buffer)
                buffer = part
            } else {
                buffer = if (buffer.isEmpty()) part else "$buffer $part"
            }
        }
        if (buffer.isNotEmpty()) chunks.add(buffer)
        return chunks
    }

    private fun splitByWords(text: String): List<String> {
        val chunks = mutableListOf<String>()
        var current = ""
        for (word in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            current = when {
                current.isEmpty() -> word
                current.length + 1 + word.length > MAX_CHARS -> { chunks.add(current); word }
                else -> "$current $word"
            }
        }
        if (current.isNotEmpty()) chunks.add(current)
        return chunks
    }

    private fun splitParagraph(paragraph: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < paragraph.length) {
            val c = paragraph[i]
            current.append(c)
            if (c in ".!?…" && isSentenceBoundary(paragraph, i)) {
                result.add(current.toString().trim())
                current.clear()
            }
            i++
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) result.add(tail)
        return result.filter { it.isNotEmpty() }
    }

    private fun isSentenceBoundary(text: String, dotIndex: Int): Boolean {
        // Consume a run of repeated terminators ("...", "?!") as one boundary.
        var end = dotIndex
        while (end + 1 < text.length && text[end + 1] in ".!?…") end++
        val next = end + 1
        if (next >= text.length) return true // end of paragraph
        if (!text[next].isWhitespace()) return false // e.g. "4.200,5" or "v.v."

        if (text[dotIndex] == '.') {
            val wordStart = run {
                var s = dotIndex - 1
                while (s >= 0 && text[s].isLetter()) s--
                s + 1
            }
            val word = text.substring(wordStart, dotIndex).lowercase()
            if (word in ABBREVIATIONS) return false
        }

        return true
    }
}
