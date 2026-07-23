package com.vieneu.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.vieneu.engine.TtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MVP harness for sub-project 1 (see docs/superpowers/specs). Only the
 * phonemizer is wired up right now — this proves the Rust/uniffi/JNI path
 * end-to-end on-device before the ONNX inference loop is added.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PhonemizeScreen()
                }
            }
        }
    }
}

@Composable
private fun PhonemizeScreen() {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var input by remember {
        mutableStateOf("Trong màn mưa mùa hạ rền vang sấm sét, một chiếc Porche màu đen chạy trên đường ở vùng quê.")
    }
    var output by remember { mutableStateOf("(chưa chạy)") }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Văn bản tiếng Việt") },
            modifier = Modifier.fillMaxSize().padding(bottom = 0.dp),
        )
        Button(
            enabled = !busy,
            onClick = {
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.Default) {
                        TtsEngine.create(context).phonemize(input)
                    }
                    output = result
                    busy = false
                }
            },
        ) {
            Text(if (busy) "Đang chạy..." else "Phonemize (test G2P core)")
        }
        Text("Phonemes:\n$output")
    }
}
