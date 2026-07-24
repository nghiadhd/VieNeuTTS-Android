@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.vieneu.reader.ui

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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vieneu.reader.ReaderApp

/** Rate/pitch/auto-advance/sleep-timer — all playback-time controls (design spec §5), never trigger regeneration. */
@Composable
fun SpeechSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ReaderApp
    val playerState by app.player.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cài đặt giọng đọc") },
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
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            ) {
                Text("Tự động chuyển chương tiếp theo", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = playerState.autoAdvanceChapter, onCheckedChange = { app.player.setAutoAdvance(it) })
            }
        }
    }
}
