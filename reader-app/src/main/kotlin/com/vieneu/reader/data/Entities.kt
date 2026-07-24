package com.vieneu.reader.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class AudioStatus { NOT_GENERATED, GENERATING, GENERATED, FAILED }

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String?,
    val epubFilePath: String,
    val coverPath: String?,
    /** Null = use [AppSettings.defaultVoice]. */
    val voiceOverride: String?,
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
)
