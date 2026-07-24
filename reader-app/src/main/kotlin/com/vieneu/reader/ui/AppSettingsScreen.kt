@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.vieneu.reader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vieneu.engine.TtsEngine
import com.vieneu.reader.ReaderApp
import kotlinx.coroutines.launch

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
