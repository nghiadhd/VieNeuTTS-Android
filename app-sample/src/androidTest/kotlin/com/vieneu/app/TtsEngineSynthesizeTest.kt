package com.vieneu.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vieneu.engine.TtsEngine
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full on-device validation of sub-project 1 (see docs/superpowers/specs):
 * real G2P via JNI + real ONNX Runtime (onnxruntime-android, not the desktop
 * artifact the JVM host tests use) on a real arm64 device/emulator. Not a
 * golden-value test (default sampling is stochastic) — proves the whole
 * pipeline actually runs and produces real audio on-device, the one thing
 * host tests structurally can't check (Rust .so + ONNX Runtime native libs
 * are both arm64-v8a/Android-only).
 */
@RunWith(AndroidJUnit4::class)
class TtsEngineSynthesizeTest {

    @Test
    fun synthesize_producesRealAudioOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = TtsEngine.create(context)

        val voices = engine.listVoices()
        assertTrue("expected 14 preset voices, got ${voices.size}", voices.size == 14)

        val text = "Xin chào, đây là bài kiểm tra tổng hợp giọng nói trên thiết bị thật."
        val t0 = System.currentTimeMillis()
        val audio = engine.synthesize(text, voice = "Thái Sơn", maxNewFrames = 300)
        val elapsedMs = System.currentTimeMillis() - t0

        val durationS = audio.size / 48000.0
        android.util.Log.i("TtsEngineTest", "Synthesized ${"%.2f".format(durationS)}s audio in ${elapsedMs}ms (RTF=${"%.3f".format((elapsedMs / 1000.0) / durationS)})")

        assertTrue("expected at least 0.5s of audio, got ${durationS}s", audio.size > 24000)
        val nonZero = audio.count { it != 0f }
        assertTrue("expected mostly non-zero samples, got $nonZero/${audio.size}", nonZero > audio.size / 2)
        val maxAbs = audio.maxOf { kotlin.math.abs(it) }
        assertTrue("expected reasonable amplitude range, max abs was $maxAbs", maxAbs in 0.01f..1.0f)

        engine.close()
    }

    /**
     * Final validation for sub-project 1: a real sentence from
     * `data/chapter-0001.txt` (the file the user dropped in specifically for
     * this), synthesized on-device and saved so it can be pulled and
     * compared by ear against the Python CPU/ONNX baseline
     * (data/reference-audio/chapter-0001_excerpt.wav).
     *
     * One sentence, not the whole paragraph/chapter: `:engine` has no
     * text-chunking yet (Python's `infer()` internally splits long text into
     * <=256-char pieces and stitches the audio back together — a distinct,
     * not-yet-ported feature that arguably belongs in the reader app rather
     * than `:engine` itself, since paragraph-at-a-time reading needs its own
     * chunk/queue logic anyway), AND — separately — this AVD's 3GB RAM is
     * tight against the fp32 backbone's footprint: the full first paragraph
     * (~430 chars) reliably OOM-kills the process (`lowmemorykiller`, ~1.15GB
     * RSS + swap) even at the default max_new_frames=300, while this single
     * opening sentence (~100 chars, matches the host FullPipelineSmokeTest)
     * completes reliably. Worth raising the AVD's RAM allocation, or
     * profiling/tuning ONNX Runtime's memory arena, as follow-up — not a
     * defect in the ported algorithm, which is separately proven bit-exact
     * against Python at every stage (see VieNeuOnnxEngine*Test).
     */
    @Test
    fun synthesize_chapterOneExcerpt_onDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = TtsEngine.create(context)

        val paragraph = "Trong màn mưa mùa hạ rền vang sấm sét, một chiếc Porche màu đen chạy trên đường ở vùng quê."

        val t0 = System.currentTimeMillis()
        val audio = engine.synthesize(paragraph, voice = "Thái Sơn", maxNewFrames = 300)
        val elapsedMs = System.currentTimeMillis() - t0
        val durationS = audio.size / 48000.0
        android.util.Log.i(
            "TtsEngineTest",
            "chapter-0001 paragraph: ${"%.2f".format(durationS)}s audio in ${elapsedMs}ms (RTF=${"%.3f".format((elapsedMs / 1000.0) / durationS)})",
        )

        assertTrue("expected at least 1s of audio, got ${durationS}s", audio.size > 48000)

        val outFile = java.io.File(context.getExternalFilesDir(null), "chapter-0001_paragraph1_android.wav")
        writeWav(outFile, audio)
        android.util.Log.i("TtsEngineTest", "Saved: ${outFile.absolutePath}")

        engine.close()
    }

    private fun writeWav(file: java.io.File, audio: FloatArray, sampleRate: Int = 48000) {
        val pcm = ShortArray(audio.size) { i -> (audio[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
        java.io.RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val dataSize = pcm.size * 2
            val byteRate = sampleRate * 2
            fun writeIntLE(v: Int) { for (i in 0..3) raf.writeByte((v shr (8 * i)) and 0xFF) }
            fun writeShortLE(v: Int) { for (i in 0..1) raf.writeByte((v shr (8 * i)) and 0xFF) }
            raf.writeBytes("RIFF"); writeIntLE(36 + dataSize); raf.writeBytes("WAVE")
            raf.writeBytes("fmt "); writeIntLE(16); writeShortLE(1); writeShortLE(1)
            writeIntLE(sampleRate); writeIntLE(byteRate); writeShortLE(2); writeShortLE(16)
            raf.writeBytes("data"); writeIntLE(dataSize)
            for (s in pcm) writeShortLE(s.toInt())
        }
    }
}
