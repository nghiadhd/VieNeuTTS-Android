@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.vieneu.reader.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
            // Highest sentence index that's actually playable.
            val lastGeneratedIndex = sentences.indexOfLast { it.audioStatus == com.vieneu.reader.data.AudioStatus.GENERATED }
            val seekableMax = lastGeneratedIndex.coerceAtLeast(0)
            var seekPreview by remember(chapterId) { mutableStateOf<Int?>(null) }
            // Track spans the FULL chapter (so the bar reads as true overall progress), but the
            // thumb value is always clamped to seekableMax — dragging past the generated portion
            // makes the thumb stick at that point instead of following the finger further right,
            // so the draggable *range* stays limited to what's actually playable.
            val displayedIndex = (seekPreview?.coerceIn(0, seekableMax)) ?: index
            val currentText = sentences.getOrNull(displayedIndex)?.text ?: ""
            val generatedCount = sentences.count { it.audioStatus == com.vieneu.reader.data.AudioStatus.GENERATED }

            // Fixed height so the slider/buttons below don't shift position every time the
            // current sentence's text is a different length — a short "Hừ!" vs. a long compound
            // sentence used to make the whole (vertically-centered) column jump around, which
            // was very noticeable while dragging the progress slider across sentences.
            Box(
                modifier = Modifier.fillMaxWidth().height(120.dp).verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center,
            ) {
                Text(currentText, style = MaterialTheme.typography.bodyLarge)
            }

            Text("Câu ${displayedIndex + 1} / $total", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
            Slider(
                value = displayedIndex.toFloat(),
                onValueChange = { seekPreview = it.toInt().coerceIn(0, seekableMax) },
                onValueChangeFinished = {
                    val c = chapter ?: return@Slider
                    val target = (seekPreview ?: index).coerceIn(0, seekableMax)
                    seekPreview = null
                    app.player.playChapter(c, target)
                },
                valueRange = 0f..(total - 1).coerceAtLeast(0).toFloat(),
                enabled = total > 0 && lastGeneratedIndex >= 0,
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
