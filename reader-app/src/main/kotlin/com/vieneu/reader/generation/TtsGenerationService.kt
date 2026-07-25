package com.vieneu.reader.generation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vieneu.engine.TtsEngine
import com.vieneu.reader.data.Book
import com.vieneu.reader.data.BookRepository
import com.vieneu.reader.data.Chapter
import com.vieneu.reader.data.Sentence
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service owning the pool of process-lifetime [TtsEngine] workers and the
 * per-sentence generation loop (design spec §2/§4). Two priority "slots" — the chapter currently
 * being listened to (high) and however many chapters are queued ahead of it (low, background
 * pre-generation) — every worker prefers the high slot over the low queue each time it looks for
 * a sentence to claim, so a listener never waits behind background work. With more than one
 * worker (AppSettings.parallelGenerationWorkers), several sentences can be generating at once —
 * each worker atomically claims its own sentence off Room (see [BookRepository.claimNextSentence])
 * so two workers never duplicate the same one.
 *
 * The UI/player never talk to this service's internals directly: generation progress is observed
 * through Room (`BookRepository.observeSentences`), which this service writes to as the single
 * source of truth. Only start/stop commands go through Intents.
 */
class TtsGenerationService : Service() {
    private lateinit var repo: BookRepository
    // Sized to MAX_WORKERS regardless of the configured worker count, so the number of real OS
    // threads is fixed and simple to reason about — unused threads just stay idle. Not the shared
    // Dispatchers.Default pool: generation is long (7-15s), CPU-heavy synthesize() calls
    // back-to-back with nothing to yield a thread on in between, and sharing Dispatchers.Default
    // with it meant the player's own decode/prefetch work (also on Dispatchers.Default) could be
    // starved of a thread for extended stretches — looking like playback silently freezing while
    // generation kept racing ahead in the background, since generation itself was never blocked.
    private val generationDispatcher = Executors.newFixedThreadPool(MAX_WORKERS).asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + generationDispatcher)
    private var enginePool: List<TtsEngine> = emptyList()
    private var workerJobs: List<Job> = emptyList()

    private val highPriorityChapterId = MutableStateFlow<Long?>(null)
    // A list, not a single nullable id: AppSettings.pregenerateChaptersAhead lets the caller
    // queue several chapters ahead of time (ListenScreen enqueues them in reading order), and
    // workers drain it front-to-back once the high-priority slot is empty.
    private val lowPriorityQueue = MutableStateFlow<List<Long>>(emptyList())

    override fun onCreate() {
        super.onCreate()
        repo = BookRepository(applicationContext)
        createNotificationChannel()
        scope.launch {
            combine(highPriorityChapterId, lowPriorityQueue) { high, low -> high to low }
                .collect { (high, low) -> GenerationStatus.update(high, low) }
        }
        scope.launch {
            // A sentence stuck at GENERATING means the process died mid-synthesize() last
            // time — reset it before workers start claiming sentences, or it'd be permanently
            // skipped (claimNextSentence only ever selects NOT_GENERATED/FAILED).
            repo.resetStaleGenerating()
            val settings = repo.getSettingsRaw()
            val workerCount = (settings?.parallelGenerationWorkers ?: 1).coerceIn(1, MAX_WORKERS)
            val threadsPerWorker = settings?.ttsThreadCount ?: 2
            enginePool = TtsEngine.createPool(applicationContext, workerCount, threadsPerWorker)
            workerJobs = enginePool.map { engine -> scope.launch { runWorker(engine) } }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_GENERATE_HIGH_PRIORITY -> {
                highPriorityChapterId.value = intent.getLongExtra(EXTRA_CHAPTER_ID, -1).takeIf { it >= 0 }
                startForeground(NOTIFICATION_ID, buildNotification("Đang tạo giọng đọc…"))
            }
            ACTION_SET_LOW_PRIORITY_CHAPTERS -> {
                // A full replace, not an append: ListenScreen calls this every time the reader
                // opens a new chapter, passing exactly the chapters it wants pre-generated from
                // *this* position. Appending instead would leave every chapter ever visited
                // stuck in the queue forever, silently competing with whatever the reader
                // actually wants prioritized right now.
                lowPriorityQueue.value = intent.getLongArrayExtra(EXTRA_CHAPTER_IDS)?.toList() ?: emptyList()
                startForeground(NOTIFICATION_ID, buildNotification("Đang tạo giọng đọc…"))
            }
            ACTION_STOP_CHAPTER -> {
                val chapterId = intent.getLongExtra(EXTRA_CHAPTER_ID, -1)
                if (highPriorityChapterId.value == chapterId) highPriorityChapterId.value = null
                lowPriorityQueue.update { queue -> queue - chapterId }
            }
            ACTION_STOP_ALL -> {
                highPriorityChapterId.value = null
                lowPriorityQueue.value = emptyList()
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        workerJobs.forEach { it.cancel() }
        // A worker that hit OOM already closed its own engine (see generateOne) before retiring
        // — runCatching absorbs a double-close on that one instead of risking onDestroy itself
        // crashing on shutdown.
        enginePool.forEach { runCatching { it.close() } }
        generationDispatcher.close()
        super.onDestroy()
    }

    private suspend fun runWorker(engine: TtsEngine) {
        while (scope.isActive) {
            val highId = highPriorityChapterId.value
            val lowId = lowPriorityQueue.value.firstOrNull()
            val chapterId = highId ?: lowId
            if (chapterId == null) {
                delay(500)
                continue
            }
            val sentence = repo.claimNextSentence(chapterId)
            if (sentence == null) {
                // No claimable sentence right now — either the chapter is genuinely done, or
                // (with more than one worker) every remaining sentence is already claimed by a
                // sibling worker. countGenerating tells the two apart: only clear the chapter out
                // of its priority slot once nothing is still in flight for it.
                if (repo.countGenerating(chapterId) == 0) {
                    if (highPriorityChapterId.value == chapterId) highPriorityChapterId.value = null
                    lowPriorityQueue.update { queue -> queue - chapterId }
                    if (highPriorityChapterId.value == null && lowPriorityQueue.value.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    }
                } else {
                    delay(200)
                }
                continue
            }
            if (!generateOne(chapterId, sentence, engine)) return // OOM — retire this worker for good.
        }
    }

    /** Returns false if this worker's engine hit an unrecoverable error and the worker should stop. */
    private suspend fun generateOne(chapterId: Long, sentence: Sentence, engine: TtsEngine): Boolean {
        val chapter = repo.getChapterOnce(chapterId) ?: return true
        val bookId = chapter.bookId
        val book = repo.getBookOnce(bookId) ?: return true

        try {
            val voice = book.voiceOverride ?: repo.getSettingsOrDefault(engine.listVoices().first()).defaultVoice
            // Temporary diagnostic for a reported "always uses default voice" bug — grep logcat
            // for VoiceDebug. Remove once root-caused.
            Log.i(
                "VoiceDebug",
                "generateOne bookId=$bookId chapterId=$chapterId sentenceId=${sentence.id} " +
                    "book.voiceOverride=${book.voiceOverride} resolvedVoice=$voice",
            )
            val audio = engine.synthesize(sentence.text, voice)
            // synthesize() is a real 7-15s CPU-bound call — if the book's voice override
            // changed while it was running, repo.setVoiceOverride already reset this sentence
            // back to NOT_GENERATED and deleted its audio out from under us. Writing this
            // (now-stale, wrong-voice) result and marking it generated would silently
            // resurrect it, undoing that reset for exactly this one sentence while every other
            // sentence correctly regenerates with the new voice. Discard instead — it's already
            // NOT_GENERATED, so a worker claims it again naturally on a later pass.
            val currentVoice = repo.getBookOnce(bookId)?.voiceOverride
                ?: repo.getSettingsOrDefault(engine.listVoices().first()).defaultVoice
            if (currentVoice != voice) return true
            val outFile = File(repo.audioDir(book.folderId, chapter.orderIndex), "s${sentence.orderIndex}.m4a")
            AacWriter.write(outFile, audio)
            val durationMs = (audio.size * 1000L / 48000L).toInt()
            repo.markGenerated(chapterId, sentence.id, outFile.name, durationMs)
            updateNotification(book, chapter)
            return true
        } catch (e: OutOfMemoryError) {
            // The process is in an unknown state after an OOM — the very next synthesize() on
            // this worker's engine would likely OOM again immediately. Drop background
            // look-ahead process-wide (every worker adds memory pressure) and retire just this
            // one worker rather than looping straight back into trouble; the others (if any)
            // keep going on the high-priority chapter the listener is actually waiting on.
            Log.e("TtsGenerationService", "generateOne OOM for sentenceId=${sentence.id}, retiring this worker", e)
            repo.markFailed(chapterId, sentence.id)
            lowPriorityQueue.value = emptyList()
            runCatching { engine.close() }
            return false
        } catch (t: Throwable) {
            Log.e("TtsGenerationService", "generateOne failed for sentenceId=${sentence.id}", t)
            repo.markFailed(chapterId, sentence.id)
            return true
        }
    }

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
        const val ACTION_SET_LOW_PRIORITY_CHAPTERS = "com.vieneu.reader.SET_LOW_PRIORITY_CHAPTERS"
        const val ACTION_STOP_CHAPTER = "com.vieneu.reader.STOP_CHAPTER"
        const val ACTION_STOP_ALL = "com.vieneu.reader.STOP_ALL"
        const val EXTRA_CHAPTER_ID = "chapterId"
        const val EXTRA_CHAPTER_IDS = "chapterIds"
        // Hard ceiling regardless of AppSettings.parallelGenerationWorkers, matching the slider's
        // max in AppSettingsScreen — bounds worst-case memory/thread usage.
        const val MAX_WORKERS = 4
        private const val CHANNEL_ID = "tts_generation"
        private const val NOTIFICATION_ID = 1001
    }
}
