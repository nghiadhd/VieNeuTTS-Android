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

    private val appScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        repository = BookRepository(this)
        // Reconstructs Book/Chapter/Sentence rows from per-chapter recovery JSON for any
        // book folder Room doesn't know about yet (DB wipe, reinstall, destructive
        // migration) — must run before anything else reads/writes through repository.
        appScope.launch { repository.runStartupRecovery() }
        player = ReaderPlayer(repository, appScope)
    }
}
