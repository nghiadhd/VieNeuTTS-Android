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
import com.vieneu.engine.TtsEngine
import com.vieneu.reader.ReaderApp
import kotlinx.coroutines.launch

/**
 * Per-book voice override, independent of the app-wide default (design spec
 * §5). Changing this deletes the book's generated audio and starts over —
 * [com.vieneu.reader.data.BookRepository.setVoiceOverride] enforces that a
 * book never mixes two voices.
 */
@Composable
fun BookVoiceScreen(bookId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ReaderApp
    val scope = rememberCoroutineScope()
    var voices by remember { mutableStateOf<List<String>>(emptyList()) }
    val book by app.repository.observeBook(bookId).collectAsState(initial = null)

    LaunchedEffect(Unit) { voices = TtsEngine.listVoicesLightweight(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Giọng đọc cho sách này") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Quay lại") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
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
