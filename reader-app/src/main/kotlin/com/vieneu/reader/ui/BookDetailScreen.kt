@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.vieneu.reader.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vieneu.reader.ReaderApp
import com.vieneu.reader.data.Chapter
import com.vieneu.reader.generation.GenerationStatus
import com.vieneu.reader.generation.TtsGenerationService
import kotlinx.coroutines.launch

@Composable
fun BookDetailScreen(bookId: Long, onBack: () -> Unit, onOpenChapter: (Long) -> Unit, onOpenBookVoice: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ReaderApp
    val scope = rememberCoroutineScope()
    val book by app.repository.observeBook(bookId).collectAsState(initial = null)
    val chapters by app.repository.observeChapters(bookId).collectAsState(initial = emptyList())
    val generatedCounts by app.repository.observeGeneratedCounts(bookId).collectAsState(initial = emptyMap())
    val generationStatus by GenerationStatus.state.collectAsState()
    var progress by remember { mutableStateOf(0 to 0) }

    LaunchedEffect(bookId, chapters) {
        progress = app.repository.wholeBookListenProgress(bookId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(book?.title ?: "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Quay lại") } },
                actions = {
                    val hasActiveWork = generationStatus.activeChapterId != null || generationStatus.queuedChapterIds.isNotEmpty()
                    if (hasActiveWork) {
                        IconButton(
                            onClick = { context.startService(Intent(context, TtsGenerationService::class.java).setAction(TtsGenerationService.ACTION_STOP_ALL)) },
                        ) { Icon(Icons.Filled.Stop, contentDescription = "Dừng tất cả việc tạo giọng đọc") }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            book?.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            val (heard, total) = progress
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                Text("Tiến độ: $heard / $total câu", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(
                    progress = { if (total > 0) heard.toFloat() / total else 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }

            TextButton(onClick = onOpenBookVoice) {
                Text("Giọng đọc riêng cho sách này: ${book?.voiceOverride ?: "(mặc định)"}")
            }

            Text("Chương", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(chapters, key = { it.id }) { chapter ->
                    // Mirrors wholeBookListenProgress's own logic, per chapter: fully heard if
                    // the cursor has moved past it, partially heard (by sentence) if it's the
                    // current chapter, untouched otherwise.
                    val b = book
                    val listenPercent = when {
                        b == null || chapter.sentenceCount == 0 -> 0
                        chapter.orderIndex < b.lastChapterIndex -> 100
                        chapter.orderIndex == b.lastChapterIndex ->
                            (b.lastSentenceIndex.coerceAtMost(chapter.sentenceCount) * 100) / chapter.sentenceCount
                        else -> 0
                    }
                    val generatedCount = generatedCounts[chapter.id] ?: 0
                    val queuePosition = generationStatus.queuedChapterIds.indexOf(chapter.id).takeIf { it >= 0 }
                    ChapterRow(
                        chapter,
                        listenPercent = listenPercent,
                        generatedCount = generatedCount,
                        isActivelyGenerating = generationStatus.activeChapterId == chapter.id,
                        queuePosition = queuePosition,
                        onClick = { onOpenChapter(chapter.id) },
                        onDeleteAudio = {
                            scope.launch {
                                app.repository.deleteChapterAudio(chapter.id)
                                app.player.notifyChapterAudioCleared(chapter.id)
                            }
                        },
                        onStopGenerating = {
                            context.startService(
                                Intent(context, TtsGenerationService::class.java)
                                    .setAction(TtsGenerationService.ACTION_STOP_CHAPTER)
                                    .putExtra(TtsGenerationService.EXTRA_CHAPTER_ID, chapter.id),
                            )
                        },
                        onAddToGenerationQueue = {
                            context.startService(
                                Intent(context, TtsGenerationService::class.java)
                                    .setAction(TtsGenerationService.ACTION_ADD_LOW_PRIORITY_CHAPTER)
                                    .putExtra(TtsGenerationService.EXTRA_CHAPTER_ID, chapter.id),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: Chapter,
    listenPercent: Int,
    generatedCount: Int,
    isActivelyGenerating: Boolean,
    queuePosition: Int?,
    onClick: () -> Unit,
    onDeleteAudio: () -> Unit,
    onStopGenerating: () -> Unit,
    onAddToGenerationQueue: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val genStatus = when {
        chapter.sentenceCount == 0 -> "—"
        generatedCount >= chapter.sentenceCount -> "Đã tạo xong"
        isActivelyGenerating -> "Đang tạo ${(generatedCount * 100) / chapter.sentenceCount}%"
        queuePosition != null -> "Đang chờ (#${queuePosition + 1} trong hàng đợi)"
        generatedCount == 0 -> "Chưa tạo giọng"
        else -> "Tạm dừng ở ${(generatedCount * 100) / chapter.sentenceCount}%"
    }

    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Text(chapter.title)
                Text(
                    "Đã nghe: $listenPercent% · $genStatus",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            val isFullyGenerated = chapter.sentenceCount > 0 && generatedCount >= chapter.sentenceCount
            if (isActivelyGenerating || queuePosition != null) {
                IconButton(onClick = onStopGenerating) {
                    Icon(Icons.Filled.Stop, contentDescription = "Dừng tạo giọng đọc cho chương này")
                }
            } else if (!isFullyGenerated && chapter.sentenceCount > 0) {
                IconButton(onClick = onAddToGenerationQueue) {
                    Icon(Icons.Filled.PlaylistAdd, contentDescription = "Thêm vào hàng đợi tạo giọng đọc")
                }
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Filled.Delete, contentDescription = "Xóa giọng đọc đã tạo cho chương này")
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Xóa giọng đọc chương này?") },
            text = { Text("Giọng đọc đã tạo cho \"${chapter.title}\" sẽ bị xóa và có thể tạo lại sau.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDeleteAudio() }) { Text("Xóa") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Hủy") }
            },
        )
    }
}
