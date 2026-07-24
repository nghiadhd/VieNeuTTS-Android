package com.vieneu.reader.generation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.vieneu.engine.TtsEngine
import com.vieneu.reader.data.AudioStatus
import com.vieneu.reader.data.Book
import com.vieneu.reader.data.BookRepository
import com.vieneu.reader.data.Chapter
import com.vieneu.reader.data.Sentence
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service owning the single, process-lifetime [TtsEngine]
 * instance and the per-sentence generation worker (design spec §2/§4).
 * Two priority "slots" — the chapter currently being listened to (high) and
 * the next chapter (low, background pre-generation) — the worker always
 * drains the high slot before touching the low one, one sentence at a time,
 * so a listener never waits behind a whole chapter of background work.
 *
 * The UI/player never talk to this service's internals directly: generation
 * progress is observed through Room (`BookRepository.observeSentences`),
 * which this service writes to as the single source of truth. Only start/
 * stop commands go through Intents.
 */
class TtsGenerationService : Service() {
    private lateinit var repo: BookRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: TtsEngine? = null
    private var workerJob: Job? = null

    private val highPriorityChapterId = MutableStateFlow<Long?>(null)
    private val lowPriorityChapterId = MutableStateFlow<Long?>(null)

    override fun onCreate() {
        super.onCreate()
        repo = BookRepository(applicationContext)
        createNotificationChannel()
        workerJob = scope.launch { runWorker() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_GENERATE_HIGH_PRIORITY -> {
                highPriorityChapterId.value = intent.getLongExtra(EXTRA_CHAPTER_ID, -1).takeIf { it >= 0 }
                startForeground(NOTIFICATION_ID, buildNotification("Đang tạo giọng đọc…"))
            }
            ACTION_GENERATE_LOW_PRIORITY -> {
                lowPriorityChapterId.value = intent.getLongExtra(EXTRA_CHAPTER_ID, -1).takeIf { it >= 0 }
                startForeground(NOTIFICATION_ID, buildNotification("Đang tạo giọng đọc…"))
            }
            ACTION_STOP_CHAPTER -> {
                val chapterId = intent.getLongExtra(EXTRA_CHAPTER_ID, -1)
                if (highPriorityChapterId.value == chapterId) highPriorityChapterId.value = null
                if (lowPriorityChapterId.value == chapterId) lowPriorityChapterId.value = null
            }
            ACTION_STOP_ALL -> {
                highPriorityChapterId.value = null
                lowPriorityChapterId.value = null
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        workerJob?.cancel()
        engine?.close()
        super.onDestroy()
    }

    private suspend fun runWorker() {
        while (scope.isActive) {
            val chapterId = highPriorityChapterId.value ?: lowPriorityChapterId.value
            if (chapterId == null) {
                delay(500)
                continue
            }
            val sentence = nextUngenerated(chapterId)
            if (sentence == null) {
                if (highPriorityChapterId.value == chapterId) highPriorityChapterId.value = null
                if (lowPriorityChapterId.value == chapterId) lowPriorityChapterId.value = null
                if (highPriorityChapterId.value == null && lowPriorityChapterId.value == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
                continue
            }
            generateOne(chapterId, sentence)
        }
    }

    private suspend fun nextUngenerated(chapterId: Long): Sentence? =
        repo.getSentencesOnce(chapterId).firstOrNull {
            it.audioStatus == AudioStatus.NOT_GENERATED || it.audioStatus == AudioStatus.FAILED
        }

    private suspend fun generateOne(chapterId: Long, sentence: Sentence) {
        val chapter = repo.getChapterOnce(chapterId) ?: return
        val bookId = chapter.bookId
        val book = repo.getBookOnce(bookId) ?: return

        try {
            repo.markGenerating(sentence.id)
            val e = getOrCreateEngine()
            val voice = book.voiceOverride ?: repo.getSettingsOrDefault(e.listVoices().first()).defaultVoice
            val audio = e.synthesize(sentence.text, voice)
            val outFile = File(repo.audioDir(bookId), "ch${chapter.orderIndex}_s${sentence.orderIndex}.wav")
            WavWriter.write(outFile, audio)
            val durationMs = (audio.size * 1000L / 48000L).toInt()
            repo.markGenerated(sentence.id, outFile.absolutePath, durationMs)
            updateNotification(book, chapter)
        } catch (t: Throwable) {
            repo.markFailed(sentence.id)
        }
    }

    private fun getOrCreateEngine(): TtsEngine = engine ?: TtsEngine.create(applicationContext).also { engine = it }

    private fun updateNotification(book: Book, chapter: Chapter) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification("${book.title} — ${chapter.title}"))
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VieNeu Reader")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Tạo giọng đọc", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_GENERATE_HIGH_PRIORITY = "com.vieneu.reader.GENERATE_HIGH"
        const val ACTION_GENERATE_LOW_PRIORITY = "com.vieneu.reader.GENERATE_LOW"
        const val ACTION_STOP_CHAPTER = "com.vieneu.reader.STOP_CHAPTER"
        const val ACTION_STOP_ALL = "com.vieneu.reader.STOP_ALL"
        const val EXTRA_CHAPTER_ID = "chapterId"
        private const val CHANNEL_ID = "tts_generation"
        private const val NOTIFICATION_ID = 1001
    }
}
