package com.vieneu.reader

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vieneu.reader.data.AudioStatus
import com.vieneu.reader.data.BookRepository
import com.vieneu.reader.generation.AacWriter
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates manual per-chapter cleanup and the distance-to-cursor automatic retention sweep —
 * see the storage-management design in docs/superpowers/specs and [BookRepository.runRetentionSweep].
 */
@RunWith(AndroidJUnit4::class)
class StorageCleanupTest {

    private fun testBookUri(context: android.content.Context): Uri =
        Uri.fromFile(File(context.getExternalFilesDir(null), "test-book.epub"))

    private fun fullNovelUri(context: android.content.Context): Uri =
        Uri.fromFile(File(context.getExternalFilesDir(null), "test-book-full.epub"))

    private suspend fun generateDummySentence(repo: BookRepository, folderId: String, chapterOrderIndex: Int, chapterId: Long, sentenceId: Long, sentenceOrderIndex: Int) {
        val outFile = File(repo.audioDir(folderId, chapterOrderIndex), "s$sentenceOrderIndex.m4a")
        AacWriter.write(outFile, FloatArray(24000)) // ~0.5s of silence — a real, small compressed file
        repo.markGenerated(chapterId, sentenceId, outFile.name, durationMs = 500)
    }

    @Test
    fun deleteChapterAudioRemovesFilesAndResetsRoom() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = BookRepository(context)

        val bookId = repo.importEpub(testBookUri(context))
        val book = repo.getBookOnce(bookId)!!
        val chapter = repo.observeChapters(bookId).first().first()
        val sentences = repo.getSentencesOnce(chapter.id).take(2)

        for (s in sentences) generateDummySentence(repo, book.folderId, chapter.orderIndex, chapter.id, s.id, s.orderIndex)

        val audioDirBefore = repo.audioDir(book.folderId, chapter.orderIndex)
        assertTrue("expected generated files before cleanup", audioDirBefore.listFiles()?.isNotEmpty() == true)

        repo.deleteChapterAudio(chapter.id)

        assertTrue("expected audio dir emptied", audioDirBefore.listFiles()?.isEmpty() != false)
        val afterSentences = repo.getSentencesOnce(chapter.id)
        assertTrue(afterSentences.all { it.audioStatus == AudioStatus.NOT_GENERATED && it.audioFilePath == null })
    }

    @Test
    fun runRetentionSweepDeletesOnlyChaptersBehindLookBehindWindow() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = BookRepository(context)
        repo.getSettingsOrDefault("Minh Đức") // ensure a settings row exists with default retentionLookBehindChapters=1

        val bookId = repo.importEpub(fullNovelUri(context))
        val book = repo.getBookOnce(bookId)!!
        val chapters = repo.observeChapters(bookId).first().sortedBy { it.orderIndex }.take(4)

        // Generate one sentence's audio in chapters 0, 1, and 2.
        for (chapter in chapters.take(3)) {
            val sentence = repo.getSentencesOnce(chapter.id).first()
            generateDummySentence(repo, book.folderId, chapter.orderIndex, chapter.id, sentence.id, sentence.orderIndex)
        }

        // Reader has advanced to chapter 3 — with the default 1-chapter look-behind, chapters
        // with orderIndex < 3-1=2 (i.e. 0 and 1) are behind the retained window; chapter 2 (== 2) is not.
        repo.updatePosition(bookId, chapterIndex = 3, sentenceIndex = 0)
        repo.runRetentionSweep(bookId)

        val chapter0After = repo.getSentencesOnce(chapters[0].id)
        val chapter1After = repo.getSentencesOnce(chapters[1].id)
        val chapter2After = repo.getSentencesOnce(chapters[2].id)

        assertTrue("chapter 0 (behind window) should have been cleaned up", chapter0After.none { it.audioStatus == AudioStatus.GENERATED })
        assertTrue("chapter 1 (behind window) should have been cleaned up", chapter1After.none { it.audioStatus == AudioStatus.GENERATED })
        assertTrue("chapter 2 (at the cursor) must NOT be auto-deleted", chapter2After.any { it.audioStatus == AudioStatus.GENERATED })
    }
}
