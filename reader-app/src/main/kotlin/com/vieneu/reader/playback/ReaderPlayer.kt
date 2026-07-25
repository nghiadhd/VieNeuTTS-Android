package com.vieneu.reader.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import com.vieneu.reader.data.AudioStatus
import com.vieneu.reader.data.BookRepository
import com.vieneu.reader.data.Chapter
import com.vieneu.reader.generation.AacReader
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Sequential, sentence-by-sentence player enforcing the streaming
 * buffer-ahead gate (design spec §4, [BufferGate]) and applying
 * speed/pitch via [AudioTrack.setPlaybackParams] — a generation-time
 * regeneration is never needed for either of those, they're playback-time
 * only. MVP playback granularity is per-sentence (pause/resume restarts the
 * current sentence rather than resuming mid-sentence — a "refine later").
 */
class ReaderPlayer(private val repo: BookRepository, private val scope: CoroutineScope) {
    enum class Status { IDLE, PLAYING, PAUSED, WAITING_FOR_BUFFER }

    data class State(
        val chapterId: Long? = null,
        val sentenceIndex: Int = 0,
        val status: Status = Status.IDLE,
        val speed: Float = 1.0f,
        val pitch: Float = 1.0f,
        val autoAdvanceChapter: Boolean = true,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Set by the UI/nav layer when a chapter finishes and auto-advance is on. */
    var onChapterFinished: (suspend (finishedChapterId: Long) -> Unit)? = null

    private var playJob: Job? = null
    private var currentChapter: Chapter? = null

    // Decoding the next sentence's AAC file (MediaCodec, real CPU cost) while the current one
    // is still playing, instead of only starting it once the current one's timer runs out —
    // otherwise that decode time becomes an audible gap between every single sentence.
    private var prefetchedSentenceId: Long? = null
    private var prefetchedPcm: Deferred<Pair<Int, ShortArray>>? = null

    private fun startPrefetch(sentenceId: Long, file: File) {
        if (prefetchedSentenceId == sentenceId) return
        clearPrefetch()
        prefetchedSentenceId = sentenceId
        prefetchedPcm = scope.async(Dispatchers.Default) { AacReader.readPcm16(file) }
    }

    private fun clearPrefetch() {
        prefetchedPcm?.cancel()
        prefetchedPcm = null
        prefetchedSentenceId = null
    }

    private suspend fun decodeOrTakePrefetch(sentenceId: Long, file: File): Pair<Int, ShortArray> {
        if (prefetchedSentenceId == sentenceId) {
            val deferred = prefetchedPcm
            prefetchedSentenceId = null
            prefetchedPcm = null
            if (deferred != null) return deferred.await()
        }
        return AacReader.readPcm16(file)
    }

    fun setSpeedPitch(speed: Float, pitch: Float) {
        _state.update { it.copy(speed = speed, pitch = pitch) }
    }

    fun setAutoAdvance(enabled: Boolean) {
        _state.update { it.copy(autoAdvanceChapter = enabled) }
    }

    fun playChapter(chapter: Chapter, startIndex: Int = 0) {
        currentChapter = chapter
        playJob?.cancel()
        clearPrefetch()
        _state.update { it.copy(chapterId = chapter.id, sentenceIndex = startIndex, status = Status.PLAYING) }
        playJob = scope.launch { playLoop(chapter, startIndex) }
        // Distance-to-cursor retention only needs to run once per chapter transition, not on
        // every per-sentence position update inside playLoop.
        scope.launch { repo.runRetentionSweep(chapter.bookId) }
    }

    fun pause() {
        playJob?.cancel()
        clearPrefetch()
        _state.update { it.copy(status = Status.PAUSED) }
    }

    fun resume() {
        val chapter = currentChapter ?: return
        val index = _state.value.sentenceIndex
        playJob?.cancel()
        _state.update { it.copy(status = Status.PLAYING) }
        playJob = scope.launch { playLoop(chapter, index) }
    }

    /**
     * Call after a chapter's audio is deleted out from under the player (manual per-chapter
     * cleanup, retention sweep). If it's the chapter currently loaded, the in-memory sentence
     * position has to be dropped too — otherwise it keeps pointing at a sentence whose file no
     * longer exists, and the UI (e.g. the progress slider) shows a stale position that survives
     * even after fresh generation restarts from sentence 0.
     */
    fun notifyChapterAudioCleared(chapterId: Long) {
        if (_state.value.chapterId != chapterId) return
        playJob?.cancel()
        clearPrefetch()
        _state.update { it.copy(sentenceIndex = 0, status = Status.IDLE) }
    }

    fun next() {
        val chapter = currentChapter ?: return
        playChapter(chapter, _state.value.sentenceIndex + 1)
    }

    fun prev() {
        val chapter = currentChapter ?: return
        playChapter(chapter, (_state.value.sentenceIndex - 1).coerceAtLeast(0))
    }

    fun stop() {
        playJob?.cancel()
        clearPrefetch()
        currentChapter = null
        _state.update { it.copy(status = Status.IDLE) }
    }

    private suspend fun playLoop(chapter: Chapter, startIndex: Int) {
        var index = startIndex
        // Only the very first sentence of this playback session needs the full cold-start
        // buffer — once we're warmed up, a momentary catch-up only needs 1 sentence ahead to
        // resume, not a full buffer rebuild (see BufferGate's doc comment).
        var warmedUp = false
        while (scope.isActive) {
            val sentences = repo.getSentencesOnce(chapter.id)
            if (index >= sentences.size) {
                _state.update { it.copy(status = Status.IDLE) }
                if (_state.value.autoAdvanceChapter) onChapterFinished?.invoke(chapter.id)
                return
            }

            val allGenerated = sentences.all { it.audioStatus == AudioStatus.GENERATED }
            val lastGeneratedIndex = sentences.indexOfLast { it.audioStatus == AudioStatus.GENERATED }
            if (!allGenerated && !BufferGate.mayPlay(sentences.size, index, lastGeneratedIndex, warmedUp)) {
                _state.update { it.copy(sentenceIndex = index, status = Status.WAITING_FOR_BUFFER) }
                delay(400)
                continue
            }

            val sentence = sentences[index]
            if (sentence.audioStatus != AudioStatus.GENERATED || sentence.audioFilePath == null) {
                delay(400) // generated out of order / retry pending — wait for it specifically
                continue
            }

            val pcmData = decodeOrTakePrefetch(sentence.id, File(sentence.audioFilePath))

            val nextSentence = sentences.getOrNull(index + 1)
            if (nextSentence != null && nextSentence.audioStatus == AudioStatus.GENERATED && nextSentence.audioFilePath != null) {
                startPrefetch(nextSentence.id, File(nextSentence.audioFilePath))
            }

            _state.update { it.copy(sentenceIndex = index, status = Status.PLAYING) }
            repo.updatePosition(chapter.bookId, chapter.orderIndex, index)
            playAacFile(pcmData, _state.value.speed, _state.value.pitch)
            // Back-to-back playback with zero gap reads as unnaturally clipped — VieNeu-TTS's
            // own Python reference inserts a pause here too, sized by boundary type (see
            // core_utils.py's V3_GAP_SILENCE): a paragraph break gets a longer breath than a
            // plain sentence end, which in turn gets more than a forced mid-sentence split.
            val pauseMs = when {
                sentence.isParagraphEnd -> 350L
                sentence.text.trim().lastOrNull() in listOf('.', '!', '?', '…') -> 180L
                else -> 40L
            }
            delay(pauseMs)
            warmedUp = true
            index++
        }
    }

    /** Blocks (suspends) until the sentence's audio finishes playing at the current speed. */
    private suspend fun playAacFile(pcmData: Pair<Int, ShortArray>, speed: Float, pitch: Float) {
        val (sampleRate, pcm) = pcmData
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            if (speed != 1.0f || pitch != 1.0f) {
                track.playbackParams = PlaybackParams().setSpeed(speed).setPitch(pitch)
            }
            track.write(pcm, 0, pcm.size)
            track.play()
            val durationMs = (pcm.size.toLong() * 1000L / sampleRate / speed).toLong()
            delay(durationMs)
            // The nominal PCM duration elapsing doesn't mean the last of it has actually left
            // the speaker yet — AudioTrack's own output pipeline (HAL buffering) adds a bit more
            // latency on top. Stopping right on the timer clips that tail on nearly every
            // sentence; poll the real head position instead, capped so a misbehaving track can't
            // hang playback indefinitely.
            val deadline = System.currentTimeMillis() + 300
            while (track.playbackHeadPosition < pcm.size && System.currentTimeMillis() < deadline) {
                delay(10)
            }
        } finally {
            track.stop()
            track.release()
        }
    }
}
