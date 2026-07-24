package com.vieneu.reader.generation

import java.io.File
import java.io.RandomAccessFile

/** Minimal 16-bit PCM mono WAV writer for [com.vieneu.engine.TtsEngine]'s float32 `[-1,1]` output. */
object WavWriter {
    fun write(file: File, audio: FloatArray, sampleRate: Int = 48000) {
        val pcm = ShortArray(audio.size) { i -> (audio[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
        RandomAccessFile(file, "rw").use { raf ->
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
