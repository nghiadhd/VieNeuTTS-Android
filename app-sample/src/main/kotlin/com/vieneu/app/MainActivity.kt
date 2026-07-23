package com.vieneu.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vieneu.engine.TtsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MVP harness for sub-project 1 (see docs/superpowers/specs): text box,
 * voice picker, Play — validates the full ported pipeline (G2P + ONNX
 * inference + playback) end-to-end on-device.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SynthesizeScreen()
                }
            }
        }
    }
}

@Composable
private fun SynthesizeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var engine by remember { mutableStateOf<TtsEngine?>(null) }
    var loadStatus by remember { mutableStateOf("Đang tải model (lần đầu có thể mất vài phút — copy ~500MB)...") }
    var voices by remember { mutableStateOf<List<String>>(emptyList()) }
    var voiceIndex by remember { mutableStateOf(0) }
    var input by remember {
        mutableStateOf("Trong màn mưa mùa hạ rền vang sấm sét, một chiếc Porche màu đen chạy trên đường ở vùng quê.")
    }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { TtsEngine.create(context) }
        engine = loaded
        voices = loaded.listVoices()
        loadStatus = "Sẵn sàng — ${voices.size} giọng"
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(loadStatus)
        if (voices.isNotEmpty()) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Giọng: ${voices[voiceIndex]}", modifier = Modifier.weight(1f))
                Button(onClick = { voiceIndex = (voiceIndex + 1) % voices.size }) { Text("Đổi giọng") }
            }
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Văn bản tiếng Việt") },
            modifier = Modifier.fillMaxWidth().height(140.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy && engine != null,
                onClick = {
                    val e = engine ?: return@Button
                    busy = true
                    status = "Đang phonemize..."
                    scope.launch {
                        val phonemes = withContext(Dispatchers.Default) { e.phonemize(input) }
                        status = "Phonemes:\n$phonemes"
                        busy = false
                    }
                },
            ) { Text("Phonemize") }

            Button(
                enabled = !busy && engine != null && voices.isNotEmpty(),
                onClick = {
                    val e = engine ?: return@Button
                    busy = true
                    status = "Đang tổng hợp giọng nói..."
                    scope.launch {
                        val (audio, ms) = withContext(Dispatchers.Default) {
                            val t0 = System.currentTimeMillis()
                            val a = e.synthesize(input, voices[voiceIndex])
                            a to (System.currentTimeMillis() - t0)
                        }
                        status = "Đã tạo ${"%.2f".format(audio.size / 48000.0)}s audio trong ${ms}ms — đang phát..."
                        withContext(Dispatchers.Default) { playPcm(audio) }
                        status = "Xong (${"%.2f".format(audio.size / 48000.0)}s, ${ms}ms tổng hợp)"
                        busy = false
                    }
                },
            ) { Text(if (busy) "Đang chạy..." else "Play") }
        }
        Text(status)
    }
}

/** Plays 48kHz mono float32 PCM in `[-1, 1]` via a blocking, one-shot [AudioTrack]. */
private fun playPcm(audio: FloatArray, sampleRate: Int = 48000) {
    val minBufferBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
    val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setBufferSizeInBytes(maxOf(minBufferBytes, audio.size * 4))
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()
    track.write(audio, 0, audio.size, AudioTrack.WRITE_BLOCKING)
    track.play()
    val durationMs = (audio.size.toLong() * 1000L / sampleRate) + 200
    Thread.sleep(durationMs)
    track.stop()
    track.release()
}
