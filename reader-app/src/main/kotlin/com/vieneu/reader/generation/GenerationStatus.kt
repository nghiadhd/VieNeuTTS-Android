package com.vieneu.reader.generation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide mirror of [TtsGenerationService]'s current high/low-priority chapters, so Compose
 * screens can show what's actively generating vs. queued and offer a Stop control. The service
 * and every UI screen always run in the same process, so a shared singleton is simpler than
 * binding to the service or round-tripping through Room for something this ephemeral.
 */
object GenerationStatus {
    data class Snapshot(val activeChapterId: Long? = null, val queuedChapterIds: List<Long> = emptyList())

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    internal fun update(activeChapterId: Long?, queuedChapterIds: List<Long>) {
        _state.value = Snapshot(activeChapterId, queuedChapterIds)
    }
}
