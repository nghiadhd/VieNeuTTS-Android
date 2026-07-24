package com.vieneu.reader.epub

import kotlin.test.assertEquals
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
}
