package com.vieneu.engine.tokenizer

/**
 * GPT-2's byte<->unicode mapping (`bytes_to_unicode()` in the original GPT-2
 * `encoder.py`, reused verbatim by HuggingFace's `ByteLevel` pre-tokenizer).
 * Printable bytes (33-126, 161-172, 174-255) map to themselves; every other
 * byte value (control chars, space, etc.) maps to a private codepoint
 * starting at 256, assigned in ascending byte order. Deterministic — no
 * table to hardcode, just this algorithm run once.
 */
object Gpt2ByteLevel {
    val byteToChar: Map<Int, Char>
    val charToByte: Map<Char, Int>

    init {
        val bytes = mutableListOf<Int>()
        bytes += ('!'.code..'~'.code)
        bytes += (0xA1..0xAC)
        bytes += (0xAE..0xFF)
        val chars = bytes.toMutableList()
        var n = 0
        for (b in 0..255) {
            if (b !in bytes) {
                bytes.add(b)
                chars.add(256 + n)
                n++
            }
        }
        val b2c = LinkedHashMap<Int, Char>()
        val c2b = LinkedHashMap<Char, Int>()
        for (idx in bytes.indices) {
            val ch = chars[idx].toChar()
            b2c[bytes[idx]] = ch
            c2b[ch] = bytes[idx]
        }
        byteToChar = b2c
        charToByte = c2b
    }

    /** UTF-8 bytes of [text] mapped through the byte-level alphabet, one char per byte. */
    fun encode(text: String): String {
        val utf8 = text.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(utf8.size)
        for (b in utf8) sb.append(byteToChar.getValue(b.toInt() and 0xFF))
        return sb.toString()
    }
}
