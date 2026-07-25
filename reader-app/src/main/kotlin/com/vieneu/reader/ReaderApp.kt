package com.vieneu.reader

import android.app.Application
import com.vieneu.reader.data.BookRepository
import com.vieneu.reader.playback.ReaderPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Simple service locator (no DI framework — small app, not worth the setup cost yet). */
class ReaderApp : Application() {
    lateinit var repository: BookRepository
        private set
    lateinit var player: ReaderPlayer
        private set

    /** Process-lifetime scope for repository operations that must run to completion
     * regardless of which screen is on top — a `rememberCoroutineScope()` in a Composable
     * gets cancelled the moment the user navigates away, which is wrong for something like
     * [BookRepository.setVoiceOverride] (loops file deletion across every chapter of the
     * book — a multi-second operation on a book with dozens of chapters, easily outlived by
     * a quick back-navigation). */
    val applicationScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        repository = BookRepository(this)
        // Reconstructs Book/Chapter/Sentence rows from per-chapter recovery JSON for any
        // book folder Room doesn't know about yet (DB wipe, reinstall, destructive
        // migration) — must run before anything else reads/writes through repository.
        applicationScope.launch { repository.runStartupRecovery() }
        player = ReaderPlayer(repository, applicationScope)
    }
}
