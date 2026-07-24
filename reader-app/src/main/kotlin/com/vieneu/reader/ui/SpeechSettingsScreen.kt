@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.vieneu.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vieneu.reader.ReaderApp
import kotlinx.coroutines.launch

private const val SUBTITLE_FONT_SCALE_MIN = 0.7f
private const val SUBTITLE_FONT_SCALE_MAX = 2.0f
private const val SUBTITLE_FONT_SCALE_STEPS = 12 // 0.7, 0.8, ..., 2.0 in steps of 0.1
private const val SUBTITLE_LINE_SPACING_MIN = 1.0f
private const val SUBTITLE_LINE_SPACING_MAX = 2.0f
private const val SUBTITLE_LINE_SPACING_STEPS = 9 // 1.0, 1.1, ..., 2.0 in steps of 0.1

/**
 * Rate/pitch/auto-advance (playback-time controls, design spec §5, never trigger regeneration)
 * plus subtitle font size/line spacing for this book specifically — the same book-scoped
 * overrides as [BookVoiceScreen], just also reachable from ListenScreen's Tune icon for
 * convenience instead of needing to go via BookDetailScreen.
 */
@Composable
fun SpeechSettingsScreen(bookId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ReaderApp
    val scope = rememberCoroutineScope()
    val playerState by app.player.state.collectAsState()
    val book by app.repository.observeBook(bookId).collectAsState(initial = null)
    val settings by app.repository.observeSettings().collectAsState(initial = null)

    val effectiveFontScale = book?.subtitleFontScaleOverride ?: settings?.subtitleFontScale ?: 1.0f
    val effectiveLineSpacing = book?.subtitleLineSpacingOverride ?: settings?.subtitleLineSpacing ?: 1.0f

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setting") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Quay lại") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
            Text("Tốc độ đọc: ${"%.2f".format(playerState.speed)}x", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = playerState.speed,
                onValueChange = { app.player.setSpeedPitch(it, playerState.pitch) },
                valueRange = 0.5f..2.0f,
                steps = 14, // 0.5, 0.6, 0.7, ..., 2.0 in steps of 0.1
            )

            Text("Cao độ giọng: ${"%.2f".format(playerState.pitch)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            Slider(
                value = playerState.pitch,
                onValueChange = { app.player.setSpeedPitch(playerState.speed, it) },
                valueRange = 0.5f..1.5f,
                steps = 9, // 0.5, 0.6, ..., 1.5 in steps of 0.1
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Cỡ chữ khi nghe: ${"%.2f".format(effectiveFontScale)}x", style = MaterialTheme.typography.titleMedium)
                TextButton(
                    enabled = book?.subtitleFontScaleOverride != null,
                    onClick = { scope.launch { app.repository.setSubtitleFontScaleOverride(bookId, null) } },
                ) { Text("Mặc định") }
            }
            Slider(
                value = effectiveFontScale,
                onValueChange = { scope.launch { app.repository.setSubtitleFontScaleOverride(bookId, it) } },
                valueRange = SUBTITLE_FONT_SCALE_MIN..SUBTITLE_FONT_SCALE_MAX,
                steps = SUBTITLE_FONT_SCALE_STEPS,
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Dãn dòng: ${"%.2f".format(effectiveLineSpacing)}x", style = MaterialTheme.typography.titleMedium)
                TextButton(
                    enabled = book?.subtitleLineSpacingOverride != null,
                    onClick = { scope.launch { app.repository.setSubtitleLineSpacingOverride(bookId, null) } },
                ) { Text("Mặc định") }
            }
            Slider(
                value = effectiveLineSpacing,
                onValueChange = { scope.launch { app.repository.setSubtitleLineSpacingOverride(bookId, it) } },
                valueRange = SUBTITLE_LINE_SPACING_MIN..SUBTITLE_LINE_SPACING_MAX,
                steps = SUBTITLE_LINE_SPACING_STEPS,
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Tự động chuyển chương tiếp theo", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = playerState.autoAdvanceChapter, onCheckedChange = { app.player.setAutoAdvance(it) })
            }
        }
    }
}
