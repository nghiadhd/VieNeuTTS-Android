package com.vieneu.reader.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter
    fun fromAudioStatus(status: AudioStatus): String = status.name

    @TypeConverter
    fun toAudioStatus(value: String): AudioStatus = AudioStatus.valueOf(value)
}

@Database(
    entities = [Book::class, Chapter::class, Sentence::class, AppSettings::class],
    version = 4,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class ReaderDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun sentenceDao(): SentenceDao
    abstract fun appSettingsDao(): AppSettingsDao

    companion object {
        @Volatile private var instance: ReaderDatabase? = null

        fun get(context: Context): ReaderDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, ReaderDatabase::class.java, "reader.db")
                    // Pre-release app, no shipped Migration path yet — schema bumps wipe local
                    // data instead; on-disk chapter metadata (see BookRepository.reconcileFromDisk)
                    // is what lets already-generated audio survive a wipe like this one.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
