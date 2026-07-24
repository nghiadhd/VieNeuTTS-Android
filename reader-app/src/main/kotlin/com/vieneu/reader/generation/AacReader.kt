package com.vieneu.reader.generation

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes the .m4a (AAC-LC) files [AacWriter] produces back to 16-bit PCM for `AudioTrack` playback. */
object AacReader {
    private const val TIMEOUT_US = 10_000L

    /** @return (sampleRate, PCM16 mono samples) — same shape the WAV reader this replaced returned. */
    fun readPcm16(file: File): Pair<Int, ShortArray> {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        try {
            val trackIndex = (0 until extractor.trackCount).first { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            val format = extractor.getTrackFormat(trackIndex)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            // configure()/start() must be inside the try — see AacWriter for why a codec that
            // fails before its try block leaks permanently and can eventually exhaust the
            // system's small pool of concurrent codec instances.
            val codec = MediaCodec.createDecoderByType(mime)
            var codecStarted = false
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                codecStarted = true

                val pcm = ByteArrayOutputStream()
                var inputDone = false
                var outputDone = false
                val bufferInfo = MediaCodec.BufferInfo()

                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val inBuf = codec.getInputBuffer(inIndex)!!
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (outIndex >= 0) {
                        if (bufferInfo.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIndex)!!
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val chunk = ByteArray(bufferInfo.size)
                            outBuf.get(chunk)
                            pcm.write(chunk)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }

                val bytes = pcm.toByteArray()
                val samples = ShortArray(bytes.size / 2)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                return sampleRate to samples
            } finally {
                if (codecStarted) codec.stop()
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }
}
