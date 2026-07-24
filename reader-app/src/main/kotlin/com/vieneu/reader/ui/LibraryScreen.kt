@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.vieneu.reader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.vieneu.reader.data.Book
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(onOpenBook: (Long) -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ReaderApp
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val books by app.repository.observeBooks().collectAsState(initial = emptyList())
    var importing by remember { mutableStateOf(false) }

    val pickEpub = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            try {
                val bookId = app.repository.importEpub(uri)
                onOpenBook(bookId)
            } finally {
                importing = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Thư viện") },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Cài đặt") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { pickEpub.launch(arrayOf("application/epub+zip")) }) {
                Icon(Icons.Filled.Add, contentDescription = "Thêm sách")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (importing) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text("Đang thêm sách…")
                }
            }
            if (books.isEmpty() && !importing) {
                Text("Chưa có sách nào. Bấm + để thêm file .epub.", modifier = Modifier.padding(16.dp))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(8.dp)) {
                items(books, key = { it.id }) { book ->
                    BookRow(
                        book,
                        onClick = { onOpenBook(book.id) },
                        onDelete = { scope.launch { app.repository.deleteBook(book.id) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun BookRow(book: Book, onClick: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        onClick = onClick,
    ) {
        Row(modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 4.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(book.title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                book.author?.let { Text(it, style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Filled.Delete, contentDescription = "Xóa sách")
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Xóa sách?") },
            text = { Text("Toàn bộ giọng đọc đã tạo cho \"${book.title}\" sẽ bị xóa.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Xóa") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Hủy") }
            },
        )
    }
}
