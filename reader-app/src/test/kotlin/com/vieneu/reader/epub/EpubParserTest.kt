package com.vieneu.reader.epub

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** `sample.epub` is a small hand-built fixture (2 chapters) — see the script in the commit that added it. */
class EpubParserTest {
    @Test
    fun parsesTitleAuthorChaptersInSpineOrder() {
        val book = File("src/test/resources/sample.epub").inputStream().use { EpubParser.parse(it) }

        assertEquals("Sach Test", book.title)
        assertEquals("Nguyen Van A", book.author)
        assertEquals(2, book.chapters.size)

        assertEquals("Chuong Mot", book.chapters[0].title)
        assertTrue(book.chapters[0].plainText.contains("Day la cau dau tien"))

        assertEquals("Chuong Hai", book.chapters[1].title)
        assertTrue(book.chapters[1].plainText.contains("Noi dung chuong hai"))
    }

    @Test
    fun chapterTextFeedsCleanlyIntoSentenceSplitter() {
        val book = File("src/test/resources/sample.epub").inputStream().use { EpubParser.parse(it) }
        val sentences = SentenceSplitter.split(book.chapters[0].plainText)
        // Both short sentences are in the same paragraph and well under the merge cap, so they
        // become a single chunk — see SentenceSplitter's doc comment for why.
        assertEquals(1, sentences.size)
        assertEquals("Day la cau dau tien cua chuong mot. Day la cau thu hai.", sentences[0].text)
    }
}
