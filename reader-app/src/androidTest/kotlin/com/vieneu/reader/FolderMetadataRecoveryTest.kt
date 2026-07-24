package com.vieneu.reader

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vieneu.reader.data.AudioStatus
import com.vieneu.reader.data.BookRepository
import com.vieneu.reader.data.ReaderDatabase
import com.vieneu.reader.generation.AacWriter
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates the per-chapter folder + recovery-JSON design (see
 * docs/superpowers/specs and [BookRepository.reconcileFromDisk]): that
 * metadata.json files are written/updated as generation progresses, and that
 * Book/Chapter/Sentence rows can be fully reconstructed from disk alone after
 * Room loses them — the whole point of this feature.
 */
@RunWith(AndroidJUnit4::class)
class FolderMetadataRecoveryTest {

    private fun testBookUri(context: android.content.Context): Uri =
        Uri.fromFile(File(context.getExternalFilesDir(null), "test-book.epub"))

    @Test
    fun importWritesBookAndChapterMetadataFiles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = BookRepository(context)

        val bookId = repo.importEpub(testBookUri(context))
        val book = repo.getBookOnce(bookId)!!
        val chapter = repo.observeChapters(bookId).first().first()

        val bookMetaFile = File(repo.booksDir(book.folderId), "book_metadata.json")
        assertTrue("expected book_metadata.json to exist", bookMetaFile.exists())
        assertTrue(bookMetaFile.readText().contains(book.folderId))

        val chapterMetaFile = File(repo.chapterDir(book.folderId, chapter.orderIndex), "metadata.json")
        assertTrue("expected chapter metadata.json to exist", chapterMetaFile.exists())
        assertTrue("expected all sentences NOT_GENERATED right after import", chapterMetaFile.readText().contains("NOT_GENERATED"))
    }

    @Test
    fun generatedAudioUpdatesChapterMetadataOnDisk() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = BookRepository(context)

        val bookId = repo.importEpub(testBookUri(context))
        val book = repo.getBookOnce(bookId)!!
        val chapter = repo.observeChapters(bookId).first().first()
        val sentence = repo.getSentencesOnce(chapter.id).first()

        val outFile = File(repo.audioDir(book.folderId, chapter.orderIndex), "s${sentence.orderIndex}.m4a")
        AacWriter.write(outFile, FloatArray(4800))
        repo.markGenerated(chapter.id, sentence.id, outFile.name, durationMs = 100)

        val metaText = File(repo.chapterDir(book.folderId, chapter.orderIndex), "metadata.json").readText()
        assertTrue(
            "expected metadata to record the generated file name",
            metaText.contains("\"audioFileName\":\"s${sentence.orderIndex}.m4a\""),
        )
        assertTrue(metaText.contains("\"durationMs\":100"))
    }

    @Test
    fun reconcileFromDiskRebuildsBookAfterRoomLoss() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = BookRepository(context)

        val bookId = repo.importEpub(testBookUri(context))
        val book = repo.getBookOnce(bookId)!!
        val chapter = repo.observeChapters(bookId).first().first()
        val sentences = repo.getSentencesOnce(chapter.id)

        // Sentence 0: generated with a real file. Sentence 1: failed. Rest: untouched.
        val s0 = sentences[0]
        val outFile = File(repo.audioDir(book.folderId, chapter.orderIndex), "s${s0.orderIndex}.m4a")
        AacWriter.write(outFile, FloatArray(4800))
        repo.markGenerated(chapter.id, s0.id, outFile.name, durationMs = 100)

        val s1 = sentences[1]
        repo.markFailed(chapter.id, s1.id)

        // Simulate "Room lost this book's rows but files are intact": delete the Book row
        // directly via the DAO, bypassing BookRepository.deleteBook (which also deletes files).
        val db = ReaderDatabase.get(context)
        db.bookDao().delete(db.bookDao().get(bookId)!!)
        assertNull("sanity check: book row should really be gone from Room", repo.getBookOnce(bookId))

        repo.reconcileFromDisk()

        val recoveredBook = repo.observeBooks().first().find { it.folderId == book.folderId }
        assertNotNull("expected book to be recovered from disk", recoveredBook)
        val recoveredChapters = repo.observeChapters(recoveredBook!!.id).first()
        assertEquals(1, recoveredChapters.size)
        val recoveredSentences = repo.getSentencesOnce(recoveredChapters.first().id)
        assertEquals(sentences.size, recoveredSentences.size)

        val recoveredS0 = recoveredSentences.find { it.orderIndex == s0.orderIndex }!!
        assertEquals("verified-generated sentence should survive recovery as GENERATED", AudioStatus.GENERATED, recoveredS0.audioStatus)
        assertNotNull(recoveredS0.audioFilePath)
        assertTrue("recovered audio path should point at a real file", File(recoveredS0.audioFilePath!!).exists())

        val recoveredS1 = recoveredSentences.find { it.orderIndex == s1.orderIndex }!!
        assertEquals("FAILED should downgrade to NOT_GENERATED on recovery", AudioStatus.NOT_GENERATED, recoveredS1.audioStatus)

        val recoveredRest = recoveredSentences.find { it.orderIndex == sentences[2].orderIndex }!!
        assertEquals(AudioStatus.NOT_GENERATED, recoveredRest.audioStatus)
    }
}
