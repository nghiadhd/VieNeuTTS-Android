package com.vieneu.reader.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: Book): Long

    @Update
    suspend fun update(book: Book)

    @Delete
    suspend fun delete(book: Book)

    @Query("SELECT * FROM books ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): Book?

    @Query("SELECT * FROM books WHERE id = :id")
    fun observe(id: Long): Flow<Book?>

    @Query("UPDATE books SET lastChapterIndex = :chapterIndex, lastSentenceIndex = :sentenceIndex WHERE id = :bookId")
    suspend fun updatePosition(bookId: Long, chapterIndex: Int, sentenceIndex: Int)

    @Query("UPDATE books SET voiceOverride = :voice WHERE id = :bookId")
    suspend fun updateVoiceOverride(bookId: Long, voice: String?)

    @Query("SELECT folderId FROM books")
    suspend fun getAllFolderIds(): List<String>
}

@Dao
interface ChapterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<Chapter>): List<Long>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY orderIndex ASC")
    fun observeForBook(bookId: Long): Flow<List<Chapter>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY orderIndex ASC")
    suspend fun getForBook(bookId: Long): List<Chapter>

    @Query("SELECT * FROM chapters WHERE id = :id")
    suspend fun get(id: Long): Chapter?

    @Query("UPDATE chapters SET audioBytes = audioBytes + :delta WHERE id = :chapterId")
    suspend fun addAudioBytes(chapterId: Long, delta: Long)

    @Query(
        "SELECT DISTINCT c.id FROM chapters c JOIN sentences s ON s.chapterId = c.id " +
            "WHERE c.bookId = :bookId AND c.orderIndex < :keepFrom AND s.audioStatus = 'GENERATED'",
    )
    suspend fun getChapterIdsWithGeneratedAudioBefore(bookId: Long, keepFrom: Int): List<Long>

    @Query("UPDATE chapters SET audioBytes = 0 WHERE id = :chapterId")
    suspend fun clearAudioBytes(chapterId: Long)
}

@Dao
interface SentenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sentences: List<Sentence>): List<Long>

    @Query("SELECT * FROM sentences WHERE chapterId = :chapterId ORDER BY orderIndex ASC")
    fun observeForChapter(chapterId: Long): Flow<List<Sentence>>

    @Query("SELECT * FROM sentences WHERE chapterId = :chapterId ORDER BY orderIndex ASC")
    suspend fun getForChapter(chapterId: Long): List<Sentence>

    @Query("UPDATE sentences SET audioStatus = :status, audioFilePath = :path, durationMs = :durationMs WHERE id = :id")
    suspend fun updateAudio(id: Long, status: AudioStatus, path: String?, durationMs: Int?)

    @Query("SELECT COUNT(*) FROM sentences WHERE chapterId = :chapterId AND audioStatus = 'GENERATED'")
    suspend fun countGenerated(chapterId: Long): Int

    @Query("UPDATE sentences SET audioStatus = 'NOT_GENERATED', audioFilePath = NULL, durationMs = NULL WHERE chapterId IN (SELECT id FROM chapters WHERE bookId = :bookId)")
    suspend fun resetAllForBook(bookId: Long)

    @Query("UPDATE sentences SET audioStatus = 'NOT_GENERATED', audioFilePath = NULL, durationMs = NULL WHERE chapterId = :chapterId")
    suspend fun resetChapter(chapterId: Long)

    @Query(
        "SELECT COUNT(*) FROM sentences WHERE chapterId IN (SELECT id FROM chapters WHERE bookId = :bookId) AND audioStatus = 'GENERATED'",
    )
    suspend fun countGeneratedForBook(bookId: Long): Int

    @Query("SELECT COUNT(*) FROM sentences WHERE chapterId IN (SELECT id FROM chapters WHERE bookId = :bookId)")
    suspend fun countTotalForBook(bookId: Long): Int

    /** A sentence stuck at GENERATING means the process died mid-synthesize() — retry it. */
    @Query("UPDATE sentences SET audioStatus = 'NOT_GENERATED' WHERE audioStatus = 'GENERATING'")
    suspend fun resetStaleGenerating()
}

@Dao
interface AppSettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: AppSettings)

    @Query("SELECT * FROM app_settings WHERE id = 0")
    fun observe(): Flow<AppSettings?>

    @Query("SELECT * FROM app_settings WHERE id = 0")
    suspend fun get(): AppSettings?
}
