package com.vieneu.reader.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import com.vieneu.reader.data.AudioStatus
import com.vieneu.reader.data.BookRepository
import com.vieneu.reader.data.Chapter
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

    fun setSpeedPitch(speed: Float, pitch: Float) {
        _state.update { it.copy(speed = speed, pitch = pitch) }
    }

    fun setAutoAdvance(enabled: Boolean) {
        _state.update { it.copy(autoAdvanceChapter = enabled) }
    }

    fun playChapter(chapter: Chapter, startIndex: Int = 0) {
        currentChapter = chapter
        playJob?.cancel()
        _state.update { it.copy(chapterId = chapter.id, sentenceIndex = startIndex, status = Status.PLAYING) }
        playJob = scope.launch { playLoop(chapter, startIndex) }
    }

    fun pause() {
        playJob?.cancel()
        _state.update { it.copy(status = Status.PAUSED) }
    }

    fun resume() {
        val chapter = currentChapter ?: return
        val index = _state.value.sentenceIndex
        playJob?.cancel()
        _state.update { it.copy(status = Status.PLAYING) }
        playJob = scope.launch { playLoop(chapter, index) }
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
        currentChapter = null
        _state.update { it.copy(status = Status.IDLE) }
    }

    private suspend fun playLoop(chapter: Chapter, startIndex: Int) {
        var index = startIndex
        while (scope.isActive) {
            val sentences = repo.getSentencesOnce(chapter.id)
            if (index >= sentences.size) {
                _state.update { it.copy(status = Status.IDLE) }
                if (_state.value.autoAdvanceChapter) onChapterFinished?.invoke(chapter.id)
                return
            }

            val allGenerated = sentences.all { it.audioStatus == AudioStatus.GENERATED }
            val lastGeneratedIndex = sentences.indexOfLast { it.audioStatus == AudioStatus.GENERATED }
            if (!allGenerated && !BufferGate.mayPlay(sentences.size, index, lastGeneratedIndex)) {
                _state.update { it.copy(sentenceIndex = index, status = Status.WAITING_FOR_BUFFER) }
                delay(400)
                continue
            }

            val sentence = sentences[index]
            if (sentence.audioStatus != AudioStatus.GENERATED || sentence.audioFilePath == null) {
                delay(400) // generated out of order / retry pending — wait for it specifically
                continue
            }

            _state.update { it.copy(sentenceIndex = index, status = Status.PLAYING) }
            repo.updatePosition(chapter.bookId, chapter.orderIndex, index)
            playWavFile(File(sentence.audioFilePath), _state.value.speed, _state.value.pitch)
            index++
        }
    }

    /** Blocks (suspends) until the WAV finishes playing at the current speed. */
    private suspend fun playWavFile(file: File, speed: Float, pitch: Float) {
        val (sampleRate, pcm) = readWavPcm16(file)
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
        } finally {
            track.stop()
            track.release()
        }
    }

    private fun readWavPcm16(file: File): Pair<Int, ShortArray> {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(44)
            raf.readFully(header)
            fun le32(off: Int) = (header[off].toInt() and 0xFF) or ((header[off + 1].toInt() and 0xFF) shl 8) or
                ((header[off + 2].toInt() and 0xFF) shl 16) or ((header[off + 3].toInt() and 0xFF) shl 24)
            val sampleRate = le32(24)
            val dataSize = le32(40)
            val bytes = ByteArray(dataSize)
            raf.readFully(bytes)
            val samples = ShortArray(dataSize / 2)
            for (i in samples.indices) {
                samples[i] = ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
            }
            return sampleRate to samples
        }
    }
}
