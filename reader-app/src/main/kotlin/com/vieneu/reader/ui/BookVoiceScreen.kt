@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.vieneu.reader.ui

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vieneu.engine.TtsEngine
import com.vieneu.reader.ReaderApp
import kotlinx.coroutines.launch

private const val SUBTITLE_FONT_SCALE_MIN = 0.75f
private const val SUBTITLE_FONT_SCALE_MAX = 2.0f
private const val SUBTITLE_LINE_SPACING_MIN = 1.0f
private const val SUBTITLE_LINE_SPACING_MAX = 2.0f

/**
 * Per-book settings, independent of the app-wide defaults (design spec §5): voice, subtitle
 * font size, and line spacing. Changing voice deletes the book's generated audio and starts
 * over — [com.vieneu.reader.data.BookRepository.setVoiceOverride] enforces that a book never
 * mixes two voices. Font size/line spacing are display-only prefs and never touch audio; the
 * sliders show/edit the *effective* value (this book's override, falling back to the app-wide
 * default), with a "Mặc định" button to clear the override and track the app default again.
 */
@Composable
fun BookVoiceScreen(bookId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ReaderApp
    val scope = rememberCoroutineScope()
    var voices by remember { mutableStateOf<List<String>>(emptyList()) }
    val book by app.repository.observeBook(bookId).collectAsState(initial = null)
    val settings by app.repository.observeSettings().collectAsState(initial = null)

    LaunchedEffect(Unit) { voices = TtsEngine.listVoicesLightweight(context) }

    val effectiveFontScale = book?.subtitleFontScaleOverride ?: settings?.subtitleFontScale ?: 1.0f
    val effectiveLineSpacing = book?.subtitleLineSpacingOverride ?: settings?.subtitleLineSpacing ?: 1.0f

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cài đặt sách này") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Quay lại") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp),
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp),
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            Text(
                "Giọng đọc cho sách này",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp),
            )
            LazyColumn {
                item {
                    ListItem(
                        headlineContent = { Text("Dùng giọng mặc định của app") },
                        leadingContent = {
                            RadioButton(
                                selected = book?.voiceOverride == null,
                                onClick = { scope.launch { app.repository.setVoiceOverride(bookId, null) } },
                            )
                        },
                    )
                }
                items(voices) { voice ->
                    ListItem(
                        headlineContent = { Text(voice) },
                        leadingContent = {
                            RadioButton(
                                selected = book?.voiceOverride == voice,
                                onClick = { scope.launch { app.repository.setVoiceOverride(bookId, voice) } },
                            )
                        },
                    )
                }
            }
        }
    }
}
