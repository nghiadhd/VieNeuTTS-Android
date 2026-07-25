package com.vieneu.reader.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class AudioStatus { NOT_GENERATED, GENERATING, GENERATED, FAILED }

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Stable UUID, independent of [id] — the on-disk folder name, so recovery survives a Room DB loss/reinstall. */
    val folderId: String,
    val title: String,
    val author: String?,
    val epubFilePath: String,
    val coverPath: String?,
    /** Null = use [AppSettings.defaultVoice]. */
    val voiceOverride: String?,
    /** Null = use [AppSettings.subtitleFontScale]. */
    val subtitleFontScaleOverride: Float? = null,
    /** Null = use [AppSettings.subtitleLineSpacing]. */
    val subtitleLineSpacingOverride: Float? = null,
    val lastChapterIndex: Int = 0,
    val lastSentenceIndex: Int = 0,
    val addedAt: Long,
)

@Entity(
    tableName = "chapters",
    foreignKeys = [ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")],
)
data class Chapter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val orderIndex: Int,
    val title: String,
    val sentenceCount: Int,
    /** Running total of this chapter's generated-audio file sizes — kept incrementally so
     * checking the global storage budget is a SUM query, not a filesystem walk over
     * potentially thousands of sentence files across every book. */
    val audioBytes: Long = 0,
)

@Entity(
    tableName = "sentences",
    foreignKeys = [ForeignKey(entity = Chapter::class, parentColumns = ["id"], childColumns = ["chapterId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("chapterId")],
)
data class Sentence(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chapterId: Long,
    val orderIndex: Int,
    val text: String,
    /** True if this is the last chunk of its source paragraph — the only pacing info that's
     * lost once text is flattened into rows (sentence- vs minor-boundary pacing is derivable
     * at playback time from this chunk's own trailing punctuation). See ReaderPlayer. */
    val isParagraphEnd: Boolean = false,
    val audioStatus: AudioStatus = AudioStatus.NOT_GENERATED,
    val audioFilePath: String? = null,
    val durationMs: Int? = null,
)

/** Single-row table (id is always 0) for app-wide defaults. */
@Entity(tableName = "app_settings")
data class AppSettings(
    @PrimaryKey val id: Int = 0,
    val defaultVoice: String,
    val defaultSpeechRate: Float = 1.0f,
    val defaultPitch: Float = 1.0f,
    val autoAdvanceChapter: Boolean = true,
    val sleepTimerMinutes: Int? = null,
    /** Auto-delete a chapter's audio once the reading cursor moves more than this many chapters past it. */
    val autoRetentionEnabled: Boolean = false,
    val retentionLookBehindChapters: Int = 1,
    /** Multiplier on the base subtitle text size in ListenScreen (1.0 = normal). */
    val subtitleFontScale: Float = 1.0f,
    /** Multiplier on the base subtitle line height in ListenScreen (1.0 = normal). */
    val subtitleLineSpacing: Float = 1.0f,
    /** ORT intra-op thread count for TTS inference. Higher can mean faster generation on
     * multi-core devices, at the cost of more CPU/battery use. ONNX Runtime's environment is a
     * process-wide singleton, so this only takes effect after the app is fully restarted, not
     * just when a new chapter starts generating. */
    val ttsThreadCount: Int = 2,
    /** How many chapters past the one currently open get queued for background (low-priority)
     * generation, so more of a book is ready before the reader gets there. */
    val pregenerateChaptersAhead: Int = 1,
    /** Number of sentences generated concurrently, each on its own thread with its own TtsEngine
     * instance. Real wall-clock speedup on multi-core devices since sentences are independent of
     * each other, at the cost of one extra ONNX-session set (RAM) and one extra CPU-bound thread
     * per additional worker. Applied on the next TtsGenerationService (re)start. */
    val parallelGenerationWorkers: Int = 1,
)
