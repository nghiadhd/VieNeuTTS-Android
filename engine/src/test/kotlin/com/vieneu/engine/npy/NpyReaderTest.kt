package com.vieneu.engine.npy

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Ground-truth values below came straight from `numpy.load(...)` on the same
 * file (see the design spec's shift-left notes) — this proves the Kotlin
 * parser reads the exact bytes numpy would, before any ONNX/Android code
 * depends on it.
 */
class NpyReaderTest {
    private val npzFile = File("src/main/assets/onnx_update/vieneu_v3_heads.npz")

    @Test
    fun parsesAllExpectedArraysWithCorrectShapesAndValues() {
        assertTrue(npzFile.exists(), "expected $npzFile to exist (bundled model asset)")
        val arrays = npzFile.inputStream().use { NpyReader.parseNpz(it) }

        assertEquals(setOf("text_emb", "audio_emb", "xvec_w", "xvec_b", "xvec_ln_w", "xvec_ln_b", "xvec_ln_eps"), arrays.keys)

        val textEmb = arrays.getValue("text_emb")
        assertEquals(listOf(419, 768), textEmb.shape.toList())
        assertFloatsClose(floatArrayOf(-0.01318359f, -0.02185059f, 0.01623535f), textEmb.data.copyOfRange(0, 3))

        val audioEmb = arrays.getValue("audio_emb")
        assertEquals(listOf(16, 1024, 768), audioEmb.shape.toList())
        assertFloatsClose(floatArrayOf(0.02954102f, 0.03857422f, -0.04174805f), audioEmb.data.copyOfRange(0, 3))

        val xvecW = arrays.getValue("xvec_w")
        assertEquals(listOf(768, 192), xvecW.shape.toList())
        assertFloatsClose(floatArrayOf(-0.02319336f, 0.00050354f, -0.02856445f), xvecW.data.copyOfRange(0, 3))

        val xvecB = arrays.getValue("xvec_b")
        assertEquals(listOf(768), xvecB.shape.toList())
        assertFloatsClose(floatArrayOf(-0.0390625f, 0.00866699f, 0.05688477f), xvecB.data.copyOfRange(0, 3))

        val xvecLnW = arrays.getValue("xvec_ln_w")
        assertFloatsClose(floatArrayOf(0.09912109f, 0.15429688f, 0.09960938f), xvecLnW.data.copyOfRange(0, 3))

        val xvecLnB = arrays.getValue("xvec_ln_b")
        assertFloatsClose(floatArrayOf(0.00157928f, -0.00750732f, 0.01757812f), xvecLnB.data.copyOfRange(0, 3))

        val xvecLnEps = arrays.getValue("xvec_ln_eps")
        assertEquals(emptyList<Int>(), xvecLnEps.shape.toList()) // scalar: shape ()
        assertEquals(1, xvecLnEps.size)
        assertFloatsClose(floatArrayOf(1e-05f), xvecLnEps.data)
    }

    private fun assertFloatsClose(expected: FloatArray, actual: FloatArray, eps: Float = 1e-6f) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertTrue(
                kotlin.math.abs(expected[i] - actual[i]) < eps,
                "index $i: expected ${expected[i]} but was ${actual[i]}",
            )
        }
    }
}
