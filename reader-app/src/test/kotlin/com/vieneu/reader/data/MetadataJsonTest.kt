package com.vieneu.reader.data

import com.vieneu.engine.tokenizer.MiniJson
import com.vieneu.engine.tokenizer.asArr
import com.vieneu.engine.tokenizer.asObj
import com.vieneu.engine.tokenizer.asStr
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class MetadataJsonTest {
    private val book = Book(
        id = 1, folderId = "folder-abc", title = "Vương Bài [C]", author = "Hà Tả",
        epubFilePath = "/x/book.epub", coverPath = null, voiceOverride = "tu_nhien",
        addedAt = 1_700_000_000_000L,
    )

    @Test
    fun bookMetadataRoundTripsAllFields() {
        val json = MiniJson.parse(MetadataJson.bookMetadata(book)).asObj()
        assertEquals("folder-abc", json["folderId"].asStr())
        assertEquals("Vương Bài [C]", json["title"].asStr())
        assertEquals("Hà Tả", json["author"].asStr())
        assertEquals("tu_nhien", json["voiceOverride"].asStr())
        assertEquals(1_700_000_000_000L, (json["addedAt"] as Double).toLong())
    }

    @Test
    fun bookMetadataWritesRealJsonNullForAbsentAuthorAndVoice() {
        val noExtras = book.copy(author = null, voiceOverride = null)
        val json = MiniJson.parse(MetadataJson.bookMetadata(noExtras)).asObj()
        assertNull(json["author"])
        assertNull(json["voiceOverride"])
    }

    @Test
    fun chapterMetadataRoundTripsMixedSentenceStatuses() {
        val chapter = Chapter(id = 10, bookId = 1, orderIndex = 0, title = "Chương 1", sentenceCount = 3)
        val sentences = listOf(
            Sentence(
                id = 100, chapterId = 10, orderIndex = 0, text = "Câu đã xong.",
                audioStatus = AudioStatus.GENERATED, audioFilePath = "/data/books/folder-abc/chapters/0/audio/s0.wav",
                durationMs = 1234,
            ),
            Sentence(id = 101, chapterId = 10, orderIndex = 1, text = "Câu thất bại.", audioStatus = AudioStatus.FAILED),
            Sentence(id = 102, chapterId = 10, orderIndex = 2, text = "Câu chưa tạo.", audioStatus = AudioStatus.NOT_GENERATED),
        )

        val json = MiniJson.parse(MetadataJson.chapterMetadata(chapter, sentences)).asObj()
        assertEquals("Chương 1", json["title"].asStr())
        assertEquals(3.0, json["sentenceCount"])

        val arr = json["sentences"].asArr()
        val s0 = arr[0].asObj()
        assertEquals("GENERATED", s0["audioStatus"].asStr())
        assertEquals("s0.wav", s0["audioFileName"].asStr())
        assertEquals(1234.0, s0["durationMs"])

        val s1 = arr[1].asObj()
        assertEquals("FAILED", s1["audioStatus"].asStr())
        assertNull(s1["audioFileName"])
        assertNull(s1["durationMs"])

        val s2 = arr[2].asObj()
        assertEquals("NOT_GENERATED", s2["audioStatus"].asStr())
        assertNull(s2["audioFileName"])
    }

    @Test
    fun escapesQuotesBackslashesNewlinesAndDiacritics() {
        val chapter = Chapter(id = 1, bookId = 1, orderIndex = 0, title = "T", sentenceCount = 1)
        val tricky = "Anh nói \"xin chào\"\\có dấu \n xuống dòng và tiếng Việt: Đường, ơn, ư."
        val sentences = listOf(Sentence(id = 1, chapterId = 1, orderIndex = 0, text = tricky))

        val json = MiniJson.parse(MetadataJson.chapterMetadata(chapter, sentences)).asObj()
        val decoded = json["sentences"].asArr()[0].asObj()["text"].asStr()
        assertEquals(tricky, decoded)
    }
}
