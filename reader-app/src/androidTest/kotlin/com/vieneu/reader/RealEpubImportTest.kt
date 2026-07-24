package com.vieneu.reader

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vieneu.reader.data.BookRepository
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-world validation using actual novel EPUBs from the truyen_dich
 * project (pushed to /sdcard/Download by adb push — see the design spec's
 * shift-left notes), not the hand-built fixture EpubParserTest already
 * covers. Exercises BookRepository.importEpub() end-to-end: copy, parse,
 * Room insert — the same code path the SAF picker flow calls, just with a
 * file:// Uri instead of a content:// one from the system picker (avoids
 * needing to automate the system file-picker UI).
 */
@RunWith(AndroidJUnit4::class)
class RealEpubImportTest {

    @Test
    fun importsSmallSingleChapterEpub() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = BookRepository(context)
        val uri = Uri.fromFile(File(context.getExternalFilesDir(null), "test-book.epub"))

        val bookId = repo.importEpub(uri)
        val book = repo.getBookOnce(bookId)
        val chapters = repo.observeChapters(bookId).first()

        android.util.Log.i("EpubImportTest", "small: title=${book?.title} author=${book?.author} chapters=${chapters.size}")
        chapters.forEach { android.util.Log.i("EpubImportTest", "  chapter: ${it.title} (${it.sentenceCount} sentences)") }

        assertTrue("expected a book title", !book?.title.isNullOrBlank())
        assertTrue("expected at least 1 chapter", chapters.isNotEmpty())
        assertTrue("expected at least 1 sentence in first chapter", chapters.first().sentenceCount > 0)
    }

    @Test
    fun importsFullMultiChapterEpub() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = BookRepository(context)
        val uri = Uri.fromFile(File(context.getExternalFilesDir(null), "test-book-full.epub"))

        val bookId = repo.importEpub(uri)
        val book = repo.getBookOnce(bookId)
        val chapters = repo.observeChapters(bookId).first()
        val totalSentences = chapters.sumOf { it.sentenceCount }

        android.util.Log.i(
            "EpubImportTest",
            "full: title=${book?.title} author=${book?.author} chapters=${chapters.size} totalSentences=$totalSentences",
        )
        chapters.take(5).forEach { android.util.Log.i("EpubImportTest", "  chapter: ${it.title} (${it.sentenceCount} sentences)") }

        assertTrue("expected a book title", !book?.title.isNullOrBlank())
        assertTrue("expected multiple chapters in a full novel epub", chapters.size > 1)
        assertTrue("expected a substantial number of sentences", totalSentences > 20)
    }
}
