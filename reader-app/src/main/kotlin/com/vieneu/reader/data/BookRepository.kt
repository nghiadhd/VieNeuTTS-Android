package com.vieneu.reader.data

import android.content.Context
import android.net.Uri
import com.vieneu.reader.epub.EpubParser
import com.vieneu.reader.epub.SentenceSplitter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Coordinates Room (metadata) + on-disk files (epub copy, per-sentence WAV
 * audio) behind one API for the UI/service layers — neither talks to
 * [ReaderDatabase] or the filesystem layout directly.
 */
class BookRepository(private val context: Context) {
    private val db = ReaderDatabase.get(context)
    private val bookDao = db.bookDao()
    private val chapterDao = db.chapterDao()
    private val sentenceDao = db.sentenceDao()
    private val settingsDao = db.appSettingsDao()

    fun observeBooks(): Flow<List<Book>> = bookDao.observeAll()
    fun observeBook(bookId: Long): Flow<Book?> = bookDao.observe(bookId)
    fun observeChapters(bookId: Long): Flow<List<Chapter>> = chapterDao.observeForBook(bookId)
    fun observeSentences(chapterId: Long): Flow<List<Sentence>> = sentenceDao.observeForChapter(chapterId)
    fun observeSettings(): Flow<AppSettings?> = settingsDao.observe()

    suspend fun getSettingsOrDefault(defaultVoice: String): AppSettings =
        settingsDao.get() ?: AppSettings(defaultVoice = defaultVoice).also { settingsDao.upsert(it) }

    suspend fun updateSettings(settings: AppSettings) = settingsDao.upsert(settings)

    fun booksDir(bookId: Long): File = File(context.filesDir, "books/$bookId")
    fun audioDir(bookId: Long): File = File(booksDir(bookId), "audio").apply { mkdirs() }

    /** Copies the picked EPUB into app storage, parses it, and inserts Book/Chapter/Sentence rows. Returns the new book id. */
    suspend fun importEpub(uri: Uri): Long = withContext(Dispatchers.IO) {
        val bookId = bookDao.insert(
            Book(title = "…", author = null, epubFilePath = "", coverPath = null, voiceOverride = null, addedAt = System.currentTimeMillis()),
        )
        val dir = booksDir(bookId).apply { mkdirs() }
        val epubFile = File(dir, "book.epub")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            epubFile.outputStream().use { output -> input.copyTo(output) }
        }

        val parsed = epubFile.inputStream().use { EpubParser.parse(it) }

        bookDao.update(
            Book(
                id = bookId, title = parsed.title, author = parsed.author, epubFilePath = epubFile.absolutePath,
                coverPath = null, voiceOverride = null, addedAt = System.currentTimeMillis(),
            ),
        )

        val chapterIds = chapterDao.insertAll(
            parsed.chapters.mapIndexed { i, ch ->
                val sentenceCount = SentenceSplitter.split(ch.plainText).size
                Chapter(bookId = bookId, orderIndex = i, title = ch.title, sentenceCount = sentenceCount)
            },
        )
        for ((i, chapter) in parsed.chapters.withIndex()) {
            val sentences = SentenceSplitter.split(chapter.plainText)
            sentenceDao.insertAll(
                sentences.mapIndexed { j, text -> Sentence(chapterId = chapterIds[i], orderIndex = j, text = text) },
            )
        }
        bookId
    }

    suspend fun updatePosition(bookId: Long, chapterIndex: Int, sentenceIndex: Int) =
        bookDao.updatePosition(bookId, chapterIndex, sentenceIndex)

    // One-shot reads for the generation worker (which reacts to state changes on
    // its own polling cadence, not a live Flow subscription).
    suspend fun getBookOnce(bookId: Long): Book? = bookDao.get(bookId)
    suspend fun getChapterOnce(chapterId: Long): Chapter? = chapterDao.get(chapterId)
    suspend fun getSentencesOnce(chapterId: Long): List<Sentence> = sentenceDao.getForChapter(chapterId)

    suspend fun markGenerating(sentenceId: Long) = sentenceDao.updateAudio(sentenceId, AudioStatus.GENERATING, null, null)
    suspend fun markGenerated(sentenceId: Long, path: String, durationMs: Int) =
        sentenceDao.updateAudio(sentenceId, AudioStatus.GENERATED, path, durationMs)
    suspend fun markFailed(sentenceId: Long) = sentenceDao.updateAudio(sentenceId, AudioStatus.FAILED, null, null)

    /** Changing a book's voice invalidates all of its generated audio — a book never mixes two voices. */
    suspend fun setVoiceOverride(bookId: Long, voice: String?) = withContext(Dispatchers.IO) {
        bookDao.updateVoiceOverride(bookId, voice)
        sentenceDao.resetAllForBook(bookId)
        audioDir(bookId).listFiles()?.forEach { it.delete() }
    }

    suspend fun deleteBook(bookId: Long) = withContext(Dispatchers.IO) {
        val book = bookDao.get(bookId) ?: return@withContext
        bookDao.delete(book)
        booksDir(bookId).deleteRecursively()
    }

    suspend fun resolveVoice(bookId: Long, defaultVoice: String): String {
        val book = bookDao.get(bookId)
        return book?.voiceOverride ?: getSettingsOrDefault(defaultVoice).defaultVoice
    }

    /** (sentences heard so far, total sentences in the book) — the whole-book progress bar's numerator/denominator. */
    suspend fun wholeBookListenProgress(bookId: Long): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val book = bookDao.get(bookId) ?: return@withContext 0 to 0
        val chapters = chapterDao.getForBook(bookId)
        val total = chapters.sumOf { it.sentenceCount }
        val heard = chapters.filter { it.orderIndex < book.lastChapterIndex }.sumOf { it.sentenceCount } +
            (chapters.getOrNull(book.lastChapterIndex)?.let { book.lastSentenceIndex.coerceAtMost(it.sentenceCount) } ?: 0)
        heard to total
    }

    suspend fun generatedProgress(bookId: Long): Pair<Int, Int> =
        sentenceDao.countGeneratedForBook(bookId) to sentenceDao.countTotalForBook(bookId)
}
