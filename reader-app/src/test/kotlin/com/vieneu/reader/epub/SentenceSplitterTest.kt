package com.vieneu.reader.epub

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class SentenceSplitterTest {
    @Test
    fun splitsBasicSentences() {
        val text = "Trong màn mưa mùa hạ rền vang sấm sét. Một chiếc Porche màu đen chạy trên đường."
        assertEquals(
            listOf(
                "Trong màn mưa mùa hạ rền vang sấm sét.",
                "Một chiếc Porche màu đen chạy trên đường.",
            ),
            SentenceSplitter.split(text),
        )
    }

    @Test
    fun handlesQuestionAndExclamation() {
        val text = "Vậy tương lai của tôi ở đâu? Nhà tù. Nói nhăng nói cuội cái gì đó!"
        assertEquals(
            listOf("Vậy tương lai của tôi ở đâu?", "Nhà tù.", "Nói nhăng nói cuội cái gì đó!"),
            SentenceSplitter.split(text),
        )
    }

    @Test
    fun doesNotSplitOnDecimalNumbers() {
        val text = "Giá SP500 hôm nay là 4.200,5 điểm. Tăng nhẹ so với hôm qua."
        assertEquals(
            listOf("Giá SP500 hôm nay là 4.200,5 điểm.", "Tăng nhẹ so với hôm qua."),
            SentenceSplitter.split(text),
        )
    }

    @Test
    fun ellipsisIsATerminatorLikeAnyOther() {
        val text = "Cô ấy dừng lại một chút… Rồi tiếp tục nói."
        assertEquals(
            listOf("Cô ấy dừng lại một chút…", "Rồi tiếp tục nói."),
            SentenceSplitter.split(text),
        )
    }

    @Test
    fun splitsOnParagraphBreaksToo() {
        val text = "Đoạn một.\n\nĐoạn hai có hai câu. Câu thứ hai."
        assertEquals(
            listOf("Đoạn một.", "Đoạn hai có hai câu.", "Câu thứ hai."),
            SentenceSplitter.split(text),
        )
    }

    @Test
    fun ignoresEmptyInput() {
        assertEquals(emptyList(), SentenceSplitter.split(""))
        assertEquals(emptyList(), SentenceSplitter.split("   \n\n  "))
    }

    @Test
    fun capsRunOnSentenceWithNoTerminatorAtCommaBoundaries() {
        // No `.!?…` anywhere, so the base splitter would emit this whole thing as
        // one 400+ char "sentence" — a real, if pathological, shape for OCR'd or
        // loosely-punctuated novel text, and the direct trigger for an oversized
        // single-shot synthesize() call on a memory-constrained device.
        val clause = "một câu rất dài không có dấu chấm câu nào cả nó cứ kéo dài mãi"
        val text = (1..8).joinToString(", ") { clause } + "."

        val result = SentenceSplitter.split(text)

        assertTrue(result.size > 1, "expected the run-on sentence to be split into multiple chunks")
        result.forEach { assertTrue(it.length <= 256, "chunk exceeds cap: '$it' (${it.length} chars)") }
        val wordCount = result.joinToString(" ").split(Regex("\\s+")).count { it.isNotBlank() }
        val expectedWordCount = text.split(Regex("[\\s,]+")).count { it.isNotBlank() }
        assertEquals(expectedWordCount, wordCount, "no words should be dropped or duplicated by chunking")
    }

    @Test
    fun capsRunOnSentenceWithNoPunctuationAtAllByWords() {
        val text = "tu " + "chu ".repeat(150) + "cuoi" // no punctuation whatsoever, ~750 chars
        val result = SentenceSplitter.split(text)

        assertTrue(result.size > 1, "expected the word-salad sentence to be split into multiple chunks")
        result.forEach { assertTrue(it.length <= 256, "chunk exceeds cap: '$it' (${it.length} chars)") }
    }

    @Test
    fun leavesShortSentencesUntouched() {
        val text = "Câu ngắn thôi."
        assertEquals(listOf("Câu ngắn thôi."), SentenceSplitter.split(text))
    }
}
