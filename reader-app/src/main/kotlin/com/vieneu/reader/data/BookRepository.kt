package com.vieneu.reader.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.vieneu.engine.tokenizer.MiniJson
import com.vieneu.engine.tokenizer.asArr
import com.vieneu.engine.tokenizer.asObj
import com.vieneu.engine.tokenizer.asStr
import com.vieneu.engine.tokenizer.asNum
import com.vieneu.reader.epub.EpubParser
import com.vieneu.reader.epub.SentenceSplitter
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coordinates Room (metadata) + on-disk files (epub copy, per-sentence WAV
 * audio, per-chapter recovery JSON) behind one API for the UI/service layers
 * — neither talks to [ReaderDatabase] or the filesystem layout directly.
 *
 * On-disk layout, keyed by [Book.folderId] (a UUID, independent of Room's
 * autoincrement id so it survives a DB wipe/reinstall — see [reconcileFromDisk]):
 * ```
 * books/{folderId}/book.epub
 * books/{folderId}/book_metadata.json
 * books/{folderId}/chapters/{chapter.orderIndex}/metadata.json
 * books/{folderId}/chapters/{chapter.orderIndex}/audio/s{sentence.orderIndex}.wav
 * ```
 */
class BookRepository(private val context: Context) {
    private val db = ReaderDatabase.get(context)
    private val bookDao = db.bookDao()
    private val chapterDao = db.chapterDao()
    private val sentenceDao = db.sentenceDao()
    private val settingsDao = db.appSettingsDao()

    private val reconcileMutex = Mutex()
    private var reconciled = false

    fun observeBooks(): Flow<List<Book>> = bookDao.observeAll()
    fun observeBook(bookId: Long): Flow<Book?> = bookDao.observe(bookId)
    fun observeChapters(bookId: Long): Flow<List<Chapter>> = chapterDao.observeForBook(bookId)
    fun observeSentences(chapterId: Long): Flow<List<Sentence>> = sentenceDao.observeForChapter(chapterId)
    fun observeSettings(): Flow<AppSettings?> = settingsDao.observe()

    suspend fun getSettingsOrDefault(defaultVoice: String): AppSettings =
        settingsDao.get() ?: AppSettings(defaultVoice = defaultVoice).also { settingsDao.upsert(it) }

    suspend fun updateSettings(settings: AppSettings) = settingsDao.upsert(settings)

    fun booksDir(folderId: String): File = File(context.filesDir, "books/$folderId")
    fun chapterDir(folderId: String, chapterOrderIndex: Int): File =
        File(booksDir(folderId), "chapters/$chapterOrderIndex").apply { mkdirs() }
    fun audioDir(folderId: String, chapterOrderIndex: Int): File =
        File(chapterDir(folderId, chapterOrderIndex), "audio").apply { mkdirs() }

    /** Copies the picked EPUB into app storage, parses it, and inserts Book/Chapter/Sentence rows. Returns the new book id. */
    suspend fun importEpub(uri: Uri): Long = withContext(Dispatchers.IO) {
        val folderId = UUID.randomUUID().toString()
        val bookId = bookDao.insert(
            Book(
                folderId = folderId, title = "…", author = null, epubFilePath = "", coverPath = null,
                voiceOverride = null, addedAt = System.currentTimeMillis(),
            ),
        )
        val dir = booksDir(folderId).apply { mkdirs() }
        val epubFile = File(dir, "book.epub")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            epubFile.outputStream().use { output -> input.copyTo(output) }
        }

        val parsed = epubFile.inputStream().use { EpubParser.parse(it) }

        val book = Book(
            id = bookId, folderId = folderId, title = parsed.title, author = parsed.author,
            epubFilePath = epubFile.absolutePath, coverPath = null, voiceOverride = null,
            addedAt = System.currentTimeMillis(),
        )
        bookDao.update(book)
        writeBookMetadata(book)

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
            val chapterRow = chapterDao.get(chapterIds[i]) ?: continue
            syncChapterMetadata(folderId, chapterRow, sentenceDao.getForChapter(chapterIds[i]))
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

    suspend fun markGenerated(chapterId: Long, sentenceId: Long, fileName: String, durationMs: Int) = withContext(Dispatchers.IO) {
        val chapter = chapterDao.get(chapterId) ?: return@withContext
        val book = bookDao.get(chapter.bookId) ?: return@withContext
        val outFile = File(audioDir(book.folderId, chapter.orderIndex), fileName)
        sentenceDao.updateAudio(sentenceId, AudioStatus.GENERATED, outFile.absolutePath, durationMs)
        chapterDao.addAudioBytes(chapterId, outFile.length())
        syncChapterMetadata(book.folderId, chapter, sentenceDao.getForChapter(chapterId))
    }

    suspend fun markFailed(chapterId: Long, sentenceId: Long) = withContext(Dispatchers.IO) {
        sentenceDao.updateAudio(sentenceId, AudioStatus.FAILED, null, null)
        val chapter = chapterDao.get(chapterId) ?: return@withContext
        val book = bookDao.get(chapter.bookId) ?: return@withContext
        syncChapterMetadata(book.folderId, chapter, sentenceDao.getForChapter(chapterId))
    }

    /** Changing a book's voice invalidates all of its generated audio — a book never mixes two voices. */
    suspend fun setVoiceOverride(bookId: Long, voice: String?) = withContext(Dispatchers.IO) {
        val book = bookDao.get(bookId) ?: return@withContext
        bookDao.updateVoiceOverride(bookId, voice)
        sentenceDao.resetAllForBook(bookId)
        for (chapter in chapterDao.getForBook(bookId)) {
            audioDir(book.folderId, chapter.orderIndex).listFiles()?.forEach { it.delete() }
            chapterDao.clearAudioBytes(chapter.id)
            syncChapterMetadata(book.folderId, chapter, sentenceDao.getForChapter(chapter.id))
        }
        writeBookMetadata(book.copy(voiceOverride = voice))
    }

    suspend fun deleteBook(bookId: Long) = withContext(Dispatchers.IO) {
        val book = bookDao.get(bookId) ?: return@withContext
        bookDao.delete(book)
        booksDir(book.folderId).deleteRecursively()
    }

    /** Manual per-chapter cleanup — the explicit user-triggered escape hatch on top of [runRetentionSweep]. */
    suspend fun deleteChapterAudio(chapterId: Long) = withContext(Dispatchers.IO) {
        val chapter = chapterDao.get(chapterId) ?: return@withContext
        val book = bookDao.get(chapter.bookId) ?: return@withContext
        sentenceDao.resetChapter(chapterId)
        audioDir(book.folderId, chapter.orderIndex).listFiles()?.forEach { it.delete() }
        chapterDao.clearAudioBytes(chapterId)
        syncChapterMetadata(book.folderId, chapter, sentenceDao.getForChapter(chapterId))
        // The saved resume position is a sentence *index* into this chapter's audio — if it still
        // points here after the audio backing it is gone, the next time this chapter is opened
        // it'll resume at that now-nonexistent sentence instead of the start.
        if (book.lastChapterIndex == chapter.orderIndex) {
            bookDao.updatePosition(book.id, chapter.orderIndex, 0)
        }
    }

    /**
     * Distance-to-cursor retention: once the reading position moves past a chapter by more
     * than [AppSettings.retentionLookBehindChapters], its audio is reclaimed automatically —
     * linear listening rarely revisits old chapters, and regenerating one is cheap relative to
     * the storage it'd otherwise hold onto forever. Chapters at/ahead of the cursor (the
     * look-ahead buffer background pre-generation exists to build) are never touched here.
     * Call this when a chapter *starts* playing, not on every per-sentence position update.
     */
    suspend fun runRetentionSweep(bookId: Long) = withContext(Dispatchers.IO) {
        val book = bookDao.get(bookId) ?: return@withContext
        val settings = settingsDao.get() ?: return@withContext
        if (!settings.autoRetentionEnabled) return@withContext
        val keepFrom = book.lastChapterIndex - settings.retentionLookBehindChapters
        // One query for candidates, not one query per chapter — a book can have 800+ chapters,
        // and an N+1 loop here was serializing against the generation worker's own writes on
        // SQLite's single-writer lock badly enough to stall generation entirely.
        for (chapterId in chapterDao.getChapterIdsWithGeneratedAudioBefore(bookId, keepFrom)) {
            deleteChapterAudio(chapterId)
        }
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

    suspend fun resetStaleGenerating() = sentenceDao.resetStaleGenerating()

    // ---- Recovery: per-chapter JSON mirrors Room so already-generated audio and
    // chapter text survive a Room DB loss (reinstall, or a future destructive
    // schema migration) without needing to re-import/re-generate from scratch. ----

    /** Call once at process start, before anything else touches [BookRepository]. */
    suspend fun runStartupRecovery() = withContext(Dispatchers.IO) {
        wipeLegacyBooksDirOnce()
        reconcileFromDisk()
    }

    /** One-time cleanup of pre-this-feature `books/{numericId}/...` folders — `adb install -r`
     * preserves filesDir across installs even though [ReaderDatabase]'s destructive migration
     * wipes Room, so without this they'd become permanently orphaned dead storage. */
    private fun wipeLegacyBooksDirOnce() {
        // v3: audio switched from raw WAV to AAC-LC (.m4a) — old .wav files are unreadable
        // under the new codec, so this bump wipes them the same way v2 wiped the old flat
        // per-book file layout.
        val marker = File(context.filesDir, ".books_layout_v3")
        if (marker.exists()) return
        File(context.filesDir, "books").deleteRecursively()
        marker.createNewFile()
    }

    suspend fun reconcileFromDisk() = reconcileMutex.withLock {
        if (reconciled) return@withLock
        reconciled = true
        val booksRoot = File(context.filesDir, "books")
        val known = bookDao.getAllFolderIds().toSet()
        booksRoot.listFiles { f -> f.isDirectory }?.forEach { dir ->
            if (dir.name !in known) recoverBookFolder(dir)
        }
    }

    private suspend fun recoverBookFolder(bookDir: File) {
        val metaFile = File(bookDir, "book_metadata.json")
        if (!metaFile.exists()) return
        try {
            val json = MiniJson.parse(metaFile.readText()).asObj()
            val bookId = bookDao.insert(
                Book(
                    folderId = bookDir.name,
                    title = json["title"].asStr(),
                    author = json["author"] as? String,
                    epubFilePath = File(bookDir, "book.epub").absolutePath,
                    coverPath = null,
                    voiceOverride = json["voiceOverride"] as? String,
                    addedAt = (json["addedAt"] as? Double)?.toLong() ?: System.currentTimeMillis(),
                ),
            )
            File(bookDir, "chapters").listFiles { f -> f.isDirectory }
                ?.mapNotNull { d -> d.name.toIntOrNull()?.let { it to d } }
                ?.sortedBy { it.first }
                ?.forEach { (orderIndex, chapterDir) -> recoverChapterFolder(bookId, orderIndex, chapterDir) }
        } catch (t: Throwable) {
            Log.e("BookRepository", "reconcile: skipping unreadable book folder ${bookDir.name}", t)
        }
    }

    private suspend fun recoverChapterFolder(bookId: Long, orderIndex: Int, chapterDir: File) {
        val metaFile = File(chapterDir, "metadata.json")
        if (!metaFile.exists()) return
        try {
            val json = MiniJson.parse(metaFile.readText()).asObj()
            val sentencesJson = json["sentences"].asArr()
            val chapterId = chapterDao.insertAll(
                listOf(Chapter(bookId = bookId, orderIndex = orderIndex, title = json["title"].asStr(), sentenceCount = sentencesJson.size)),
            ).first()
            val audioDir = File(chapterDir, "audio")
            val sentences = sentencesJson.map { entry ->
                val obj = entry.asObj()
                val fileName = obj["audioFileName"] as? String
                val audioFile = fileName?.let { File(audioDir, it) }
                val claimed = AudioStatus.valueOf(obj["audioStatus"].asStr())
                val verified = claimed == AudioStatus.GENERATED && audioFile?.exists() == true
                Sentence(
                    chapterId = chapterId,
                    orderIndex = obj["orderIndex"].asNum(),
                    text = obj["text"].asStr(),
                    audioStatus = if (verified) AudioStatus.GENERATED else AudioStatus.NOT_GENERATED,
                    audioFilePath = if (verified) audioFile!!.absolutePath else null,
                    durationMs = (obj["durationMs"] as? Double)?.toInt(),
                )
            }
            sentenceDao.insertAll(sentences)
            val recoveredBytes = sentences.sumOf { s -> s.audioFilePath?.let { File(it).length() } ?: 0L }
            if (recoveredBytes > 0) chapterDao.addAudioBytes(chapterId, recoveredBytes)
        } catch (t: Throwable) {
            Log.e("BookRepository", "reconcile: skipping unreadable chapter folder ${chapterDir.name}", t)
        }
    }

    private fun writeBookMetadata(book: Book) {
        writeJsonAtomically(File(booksDir(book.folderId), "book_metadata.json"), MetadataJson.bookMetadata(book))
    }

    private fun syncChapterMetadata(folderId: String, chapter: Chapter, sentences: List<Sentence>) {
        writeJsonAtomically(File(chapterDir(folderId, chapter.orderIndex), "metadata.json"), MetadataJson.chapterMetadata(chapter, sentences))
    }

    private fun writeJsonAtomically(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        tmp.renameTo(target)
    }
}
