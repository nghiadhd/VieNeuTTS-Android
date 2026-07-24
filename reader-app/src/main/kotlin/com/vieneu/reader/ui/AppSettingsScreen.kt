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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.vieneu.reader.data.AppSettings
import kotlinx.coroutines.launch

private const val SUBTITLE_FONT_SCALE_MIN = 0.7f
private const val SUBTITLE_FONT_SCALE_MAX = 2.0f
private const val SUBTITLE_FONT_SCALE_STEPS = 12 // 0.7, 0.8, ..., 2.0 in steps of 0.1
private const val SUBTITLE_LINE_SPACING_MIN = 1.0f
private const val SUBTITLE_LINE_SPACING_MAX = 2.0f
private const val SUBTITLE_LINE_SPACING_STEPS = 9 // 1.0, 1.1, ..., 2.0 in steps of 0.1

@Composable
fun AppSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ReaderApp
    val scope = rememberCoroutineScope()
    var voices by remember { mutableStateOf<List<String>>(emptyList()) }
    val settings by app.repository.observeSettings().collectAsState(initial = null)

    LaunchedEffect(Unit) {
        voices = TtsEngine.listVoicesLightweight(context)
        if (voices.isNotEmpty()) app.repository.getSettingsOrDefault(voices.first())
    }

    fun update(change: (AppSettings) -> AppSettings) {
        val current = settings ?: return
        scope.launch { app.repository.updateSettings(change(current)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cài đặt") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Quay lại") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                "Bộ nhớ",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Tự động xóa chương đã nghe qua")
                Switch(
                    checked = settings?.autoRetentionEnabled ?: false,
                    onCheckedChange = { enabled -> update { it.copy(autoRetentionEnabled = enabled) } },
                )
            }

            Text(
                "Cỡ chữ khi nghe: ${"%.2f".format(settings?.subtitleFontScale ?: 1.0f)}x",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
            )
            Slider(
                value = settings?.subtitleFontScale ?: 1.0f,
                onValueChange = { update { s -> s.copy(subtitleFontScale = it) } },
                valueRange = SUBTITLE_FONT_SCALE_MIN..SUBTITLE_FONT_SCALE_MAX,
                steps = SUBTITLE_FONT_SCALE_STEPS,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Text(
                "Dãn dòng: ${"%.2f".format(settings?.subtitleLineSpacing ?: 1.0f)}x",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
            )
            Slider(
                value = settings?.subtitleLineSpacing ?: 1.0f,
                onValueChange = { update { s -> s.copy(subtitleLineSpacing = it) } },
                valueRange = SUBTITLE_LINE_SPACING_MIN..SUBTITLE_LINE_SPACING_MAX,
                steps = SUBTITLE_LINE_SPACING_STEPS,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            Text(
                "Giọng đọc mặc định cho sách mới",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp),
            )
            LazyColumn {
                items(voices) { voice ->
                    ListItem(
                        headlineContent = { Text(voice) },
                        leadingContent = {
                            RadioButton(
                                selected = settings?.defaultVoice == voice,
                                onClick = {
                                    scope.launch {
                                        val current = settings ?: app.repository.getSettingsOrDefault(voice)
                                        app.repository.updateSettings(current.copy(defaultVoice = voice))
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}
