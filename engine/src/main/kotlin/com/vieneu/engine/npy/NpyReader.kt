package com.vieneu.engine.npy

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/** A single array read out of an `.npy`/`.npz` entry: `<f4` (float32), C order only. */
data class NpyArray(val shape: IntArray, val data: FloatArray) {
    val size: Int get() = data.size
}

/**
 * Minimal reader for the numpy `.npy`/`.npz` formats produced by
 * `numpy.savez` — just enough to load `vieneu_v3_heads.npz`
 * (`text_emb`/`audio_emb`/`xvec_*`, all little-endian float32, C-order).
 * Not a general-purpose numpy reader: only `<f4` is supported, matching
 * what VieNeu-TTS actually exports.
 */
object NpyReader {
    private val MAGIC = byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte())
    private val SHAPE_RE = Regex("'shape':\\s*\\(([^)]*)\\)")
    private val DESCR_RE = Regex("'descr':\\s*'([^']*)'")
    private val FORTRAN_RE = Regex("'fortran_order':\\s*(True|False)")

    /** Parses one `.npy` file's full bytes (header + data). */
    fun parseNpy(bytes: ByteArray): NpyArray {
        require(bytes.size >= 10 && bytes.sliceArray(0 until 6).contentEquals(MAGIC)) {
            "Not a valid .npy file (bad magic)"
        }
        val major = bytes[6].toInt()
        val headerLenSize: Int
        val headerLen: Int
        val headerStart: Int
        if (major == 1) {
            headerLenSize = 2
            headerLen = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
            headerStart = 10
        } else {
            headerLenSize = 4
            headerLen = (bytes[8].toInt() and 0xFF) or
                ((bytes[9].toInt() and 0xFF) shl 8) or
                ((bytes[10].toInt() and 0xFF) shl 16) or
                ((bytes[11].toInt() and 0xFF) shl 24)
            headerStart = 12
        }
        check(headerLenSize > 0) // silence unused warning without changing structure
        val header = String(bytes, headerStart, headerLen, Charsets.US_ASCII)

        val descr = DESCR_RE.find(header)?.groupValues?.get(1)
            ?: error("npy header missing 'descr': $header")
        require(descr == "<f4") { "Only little-endian float32 (<f4) is supported, got '$descr'" }

        val fortran = FORTRAN_RE.find(header)?.groupValues?.get(1) == "True"
        require(!fortran) { "Fortran-ordered arrays are not supported" }

        val shapeStr = SHAPE_RE.find(header)?.groupValues?.get(1)
            ?: error("npy header missing 'shape': $header")
        val shape = shapeStr.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.toInt() }
            .toIntArray()

        val dataStart = headerStart + headerLen
        val count = if (shape.isEmpty()) 1 else shape.fold(1) { acc, d -> acc * d }
        val buf = ByteBuffer.wrap(bytes, dataStart, count * 4).order(ByteOrder.LITTLE_ENDIAN)
        val data = FloatArray(count)
        for (i in 0 until count) data[i] = buf.getFloat(dataStart + i * 4)
        return NpyArray(shape, data)
    }

    /** Reads every `.npy` entry out of an `.npz` (zip) stream, keyed by entry name without `.npy`. */
    fun parseNpz(input: InputStream): Map<String, NpyArray> {
        val result = mutableMapOf<String, NpyArray>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val bytes = zip.readBytes()
                    val name = entry.name.removeSuffix(".npy")
                    result[name] = parseNpy(bytes)
                }
                entry = zip.nextEntry
            }
        }
        return result
    }
}
