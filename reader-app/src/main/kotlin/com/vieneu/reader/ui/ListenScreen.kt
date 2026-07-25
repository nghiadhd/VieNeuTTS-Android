@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.vieneu.reader.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
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
fun ListenScreen(bookId: Long, chapterId: Long, onBack: () -> Unit, onOpenBookSettings: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ReaderApp
    val book by app.repository.observeBook(bookId).collectAsState(initial = null)
    val chapters by app.repository.observeChapters(bookId).collectAsState(initial = emptyList())
    val chapter = chapters.find { it.id == chapterId }
    val sentences by app.repository.observeSentences(chapterId).collectAsState(initial = emptyList())
    val playerState by app.player.state.collectAsState()
    val settings by app.repository.observeSettings().collectAsState(initial = null)
    val subtitleFontScale = book?.subtitleFontScaleOverride ?: settings?.subtitleFontScale ?: 1.0f
    val subtitleLineSpacing = book?.subtitleLineSpacingOverride ?: settings?.subtitleLineSpacing ?: 1.0f

    // Start high-priority generation for this chapter, and replace the whole background
    // (low-priority) queue with the next N chapters from *this* position (AppSettings.
    // pregenerateChaptersAhead, configurable in AppSettingsScreen's Performance section) —
    // design spec §2/§4. A replace, not an append: otherwise every chapter ever opened during a
    // reading session stays queued forever, competing with wherever the reader actually is now.
    // Keyed on chapter?.orderIndex, not the whole Chapter object: Chapter.audioBytes increments
    // on every single sentence generated (see BookRepository.markGenerated), so keying on the
    // full object would restart this effect — and re-send both intents — on every sentence
    // instead of only when the reader actually opens a different chapter.
    LaunchedEffect(chapterId, chapter?.orderIndex, settings?.pregenerateChaptersAhead) {
        val c = chapter ?: return@LaunchedEffect // chapters Flow hasn't loaded yet; wait for it
        context.startService(
            Intent(context, TtsGenerationService::class.java)
                .setAction(TtsGenerationService.ACTION_GENERATE_HIGH_PRIORITY)
                .putExtra(TtsGenerationService.EXTRA_CHAPTER_ID, chapterId),
        )
        val aheadCount = settings?.pregenerateChaptersAhead ?: 1
        val aheadChapterIds = (1..aheadCount)
            .mapNotNull { offset -> chapters.getOrNull(c.orderIndex + offset)?.id }
            .toLongArray()
        context.startService(
            Intent(context, TtsGenerationService::class.java)
                .setAction(TtsGenerationService.ACTION_SET_LOW_PRIORITY_CHAPTERS)
                .putExtra(TtsGenerationService.EXTRA_CHAPTER_IDS, aheadChapterIds),
        )
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
                    IconButton(onClick = onOpenBookSettings) { Icon(Icons.Filled.Tune, contentDescription = "Cài đặt phát") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
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

            // Fills all space between the top bar and the slider/controls below, rather than a
            // small fixed box — weight(1f) means its size never depends on the current
            // sentence's text length, so the slider/buttons below it don't shift position as
            // that length varies (short "Hừ!" vs. a long compound sentence), while still using
            // the full available reading area instead of just a slice of it.
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    currentText,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * subtitleFontScale,
                        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * subtitleFontScale * subtitleLineSpacing,
                    ),
                )
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
                // Three layers instead of the stock two-tone track, so the bar itself shows
                // generation racing ahead of (or falling behind) playback, replacing the
                // separate "Đang tạo thêm giọng đọc…" spinner/label that used to appear below:
                // background = not generated yet, middle = generated but not played yet
                // (the actual look-ahead buffer), foreground = already played.
                track = {
                    val maxIndex = (total - 1).coerceAtLeast(1)
                    val playedFraction = (displayedIndex.toFloat() / maxIndex).coerceIn(0f, 1f)
                    val generatedFraction = (seekableMax.toFloat() / maxIndex).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth().height(4.dp)) {
                        Box(
                            Modifier.fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(2.dp)),
                        )
                        Box(
                            Modifier.fillMaxWidth(generatedFraction).fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(2.dp)),
                        )
                        Box(
                            Modifier.fillMaxWidth(playedFraction).fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            Text(
                "Đã tạo giọng: $generatedCount / $total câu",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )

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
