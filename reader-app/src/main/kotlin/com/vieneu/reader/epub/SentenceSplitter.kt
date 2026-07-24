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
 */
object SentenceSplitter {
    private val ABBREVIATIONS = setOf("ts", "ths", "gs", "pgs", "vs", "tp", "q", "no")

    fun split(text: String): List<String> {
        val paragraphs = text.split(Regex("\\r?\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        val sentences = mutableListOf<String>()
        for (paragraph in paragraphs) sentences.addAll(splitParagraph(paragraph))
        return sentences
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
