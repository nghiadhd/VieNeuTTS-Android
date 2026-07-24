package com.vieneu.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vieneu.reader.generation.AacReader
import com.vieneu.reader.generation.AacWriter
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates the AAC-LC codec swap (see docs/superpowers/specs and the storage-cost analysis
 * that motivated it — raw WAV averaged ~450KB/sentence on device, ~119MB for one 264-sentence
 * chapter): encode/decode round-trips with acceptable fidelity for speech, and the compressed
 * file is meaningfully smaller than the WAV it replaced.
 */
@RunWith(AndroidJUnit4::class)
class AacRoundTripTest {
    private val sampleRate = 48000

    private fun sineWave(seconds: Double, hz: Double = 440.0): FloatArray {
        val n = (sampleRate * seconds).toInt()
        return FloatArray(n) { i -> (sin(2 * PI * hz * i / sampleRate) * 0.5).toFloat() }
    }

    @Test
    fun encodesAndDecodesWithAcceptableFidelity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // 10s ~ a real TTS sentence, and long enough to span many MediaCodec input buffers —
        // a 1s clip previously fit in too few buffer-fills to exercise the byte-alignment bug
        // this test caught (see AacWriter's chunk-size rounding).
        val original = sineWave(seconds = 10.0)
        val file = File(context.cacheDir, "aac_roundtrip_test.m4a")

        AacWriter.write(file, original, sampleRate)
        val (decodedRate, pcm) = AacReader.readPcm16(file)

        assertEquals(sampleRate, decodedRate)
        // AAC encoders introduce priming/lookahead delay (typically ~1-2 frames, up to a couple
        // thousand samples) that shifts the decoded stream relative to the input — well-known
        // codec behavior, not a bug, and irrelevant for spoken-sentence playback. So this checks
        // preserved *energy* (did the compression roughly keep the same loudness/content) rather
        // than requiring sample-exact alignment, which a naive index-to-index comparison can't
        // give across that delay.
        assertTrue("sample count drifted too much: ${pcm.size} vs ${original.size}", kotlin.math.abs(pcm.size - original.size) < 4096)

        fun rms(samples: FloatArray) = sqrt(samples.sumOf { (it * it).toDouble() } / samples.size)
        fun rmsShort(samples: ShortArray) = sqrt(samples.sumOf { (it.toDouble() * it) } / samples.size)

        val originalRms = rms(original) * 32767.0
        val decodedRms = rmsShort(pcm)
        val ratio = decodedRms / originalRms
        assertTrue("decoded RMS energy drifted too far from original: ratio=$ratio (orig=$originalRms, decoded=$decodedRms)", ratio in 0.7..1.3)
    }

    @Test
    fun noByteSplitGlitchesAcrossManyEncoderInputBuffers() {
        // A smooth sine wave has a bounded, predictable sample-to-sample delta. A 16-bit PCM
        // sample split across two separate queueInputBuffer calls (the actual bug found here —
        // inBuf.capacity() isn't guaranteed even, so a naive chunk size can misalign the byte
        // stream) produces a garbage reinterpreted sample at that exact point: a huge, sudden
        // jump nothing like the smooth waveform around it. A long clip forces many buffer-fills,
        // so any single misaligned boundary shows up as an outlier here.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = sineWave(seconds = 10.0)
        val file = File(context.cacheDir, "aac_glitch_test.m4a")

        AacWriter.write(file, original, sampleRate)
        val (_, pcm) = AacReader.readPcm16(file)

        // Expected max delta for a 440Hz/0.5-amplitude sine at 48kHz is ~950; well-clear ceiling.
        val maxExpectedDelta = 6000
        var worst = 0
        for (i in 1 until pcm.size) {
            val delta = kotlin.math.abs(pcm[i] - pcm[i - 1])
            if (delta > worst) worst = delta
        }
        assertTrue("found a glitch-sized sample discontinuity: worst consecutive delta=$worst", worst < maxExpectedDelta)
    }

    @Test
    fun compressedFileIsMuchSmallerThanEquivalentWav() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = sineWave(seconds = 5.0)
        val file = File(context.cacheDir, "aac_size_test.m4a")

        AacWriter.write(file, original, sampleRate)

        val equivalentWavBytes = original.size * 2L // 16-bit PCM, mono
        assertTrue(
            "expected AAC-LC to be at least 5x smaller than raw PCM (was ${file.length()} vs $equivalentWavBytes)",
            file.length() * 5 < equivalentWavBytes,
        )
    }
}
