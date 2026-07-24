package com.vieneu.reader

import android.app.Application
import com.vieneu.reader.data.BookRepository
import com.vieneu.reader.playback.ReaderPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

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
        player = ReaderPlayer(repository, appScope)
    }
}
