@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.vieneu.reader.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vieneu.reader.ReaderApp
import com.vieneu.reader.data.Chapter
import com.vieneu.reader.generation.TtsGenerationService
import com.vieneu.reader.playback.ReaderPlayer

@Composable
fun ListenScreen(bookId: Long, chapterId: Long, onBack: () -> Unit, onOpenSpeechSettings: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ReaderApp
    val book by app.repository.observeBook(bookId).collectAsState(initial = null)
    val chapters by app.repository.observeChapters(bookId).collectAsState(initial = emptyList())
    val chapter = chapters.find { it.id == chapterId }
    val sentences by app.repository.observeSentences(chapterId).collectAsState(initial = emptyList())
    val playerState by app.player.state.collectAsState()

    // Start high-priority generation for this chapter, and kick off the next
    // chapter in the background (low priority) — design spec §2/§4.
    LaunchedEffect(chapterId) {
        context.startService(
            Intent(context, TtsGenerationService::class.java)
                .setAction(TtsGenerationService.ACTION_GENERATE_HIGH_PRIORITY)
                .putExtra(TtsGenerationService.EXTRA_CHAPTER_ID, chapterId),
        )
        val nextChapter = chapters.getOrNull((chapter?.orderIndex ?: -2) + 1)
        if (nextChapter != null) {
            context.startService(
                Intent(context, TtsGenerationService::class.java)
                    .setAction(TtsGenerationService.ACTION_GENERATE_LOW_PRIORITY)
                    .putExtra(TtsGenerationService.EXTRA_CHAPTER_ID, nextChapter.id),
            )
        }
    }

    LaunchedEffect(chapterId, chapter, book) {
        val c = chapter ?: return@LaunchedEffect
        val b = book ?: return@LaunchedEffect
        if (app.player.state.value.chapterId != chapterId) {
            val startIndex = if (b.lastChapterIndex == c.orderIndex) b.lastSentenceIndex else 0
            app.player.playChapter(c, startIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(chapter?.title ?: "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Quay lại") } },
                actions = {
                    IconButton(onClick = onOpenSpeechSettings) { Icon(Icons.Filled.Tune, contentDescription = "Cài đặt phát") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val total = sentences.size
            val index = playerState.sentenceIndex.coerceIn(0, (total - 1).coerceAtLeast(0))
            val currentText = sentences.getOrNull(index)?.text ?: ""
            val generatedCount = sentences.count { it.audioStatus == com.vieneu.reader.data.AudioStatus.GENERATED }

            Text(currentText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 16.dp))

            Text("Câu ${index + 1} / $total", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(
                progress = { if (total > 0) (index + 1).toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            Text(
                "Đã tạo giọng: $generatedCount / $total câu",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )

            if (playerState.status == ReaderPlayer.Status.WAITING_FOR_BUFFER) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text("Đang tạo thêm giọng đọc…")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconButton(onClick = { app.player.prev() }) { Icon(Icons.Filled.SkipPrevious, contentDescription = "Câu trước") }
                IconButton(
                    onClick = {
                        if (playerState.status == ReaderPlayer.Status.PLAYING) app.player.pause() else app.player.resume()
                    },
                ) {
                    Icon(
                        if (playerState.status == ReaderPlayer.Status.PLAYING) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Phát/Tạm dừng",
                    )
                }
                IconButton(onClick = { app.player.next() }) { Icon(Icons.Filled.SkipNext, contentDescription = "Câu tiếp") }
            }
        }
    }
}
