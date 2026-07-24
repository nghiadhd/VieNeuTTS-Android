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
import androidx.compose.material3.Switch
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
import com.vieneu.reader.data.AppSettings
import kotlinx.coroutines.launch

private val STORAGE_BUDGET_PRESETS_MB = listOf(1024, 2048, 4096, 8192)
private val SUBTITLE_FONT_SCALE_PRESETS = listOf(0.85f to "Nhỏ", 1.0f to "Vừa", 1.25f to "Lớn", 1.5f to "Rất lớn")

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
                    checked = settings?.autoRetentionEnabled ?: true,
                    onCheckedChange = { enabled -> update { it.copy(autoRetentionEnabled = enabled) } },
                )
            }
            Text(
                "Giới hạn dung lượng cho tạo trước ở chế độ nền",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
            )
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                STORAGE_BUDGET_PRESETS_MB.forEach { mb ->
                    TextButton(onClick = { update { it.copy(storageBudgetMb = mb) } }) {
                        val label = if (mb >= 1024) "${mb / 1024}GB" else "${mb}MB"
                        Text(if (settings?.storageBudgetMb == mb) "[$label]" else label)
                    }
                }
            }

            Text(
                "Cỡ chữ khi nghe",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
            )
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                SUBTITLE_FONT_SCALE_PRESETS.forEach { (scale, label) ->
                    TextButton(onClick = { update { it.copy(subtitleFontScale = scale) } }) {
                        Text(if (settings?.subtitleFontScale == scale) "[$label]" else label)
                    }
                }
            }

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
