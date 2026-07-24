package com.vieneu.reader.generation

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes [TtsEngine][com.vieneu.engine.TtsEngine]'s float32 `[-1,1]` output to AAC-LC (mono)
 * in an .m4a container, via the framework `MediaCodec`/`MediaMuxer` APIs — no third-party codec
 * dependency, guaranteed available since API 16. Still ~5-6x smaller than the raw 16-bit PCM WAV
 * this replaced, at a CPU cost of single-digit milliseconds — negligible next to the 7-15s of
 * TTS inference that produces the audio in the first place.
 *
 * 128kbps, not the more aggressive 32kbps first tried: AAC-LC at 48kHz (this engine's native
 * output rate, not downsampled) needs to spread its bit budget across the full ~24kHz Nyquist
 * bandwidth with no SBR to fall back on (SBR only kicks in on HE-AAC profiles, not the AACObjectLC
 * used here) — 32kbps at that bandwidth produced audible quantization noise ("static"), confirmed
 * by real listening after synthetic-sine-wave tests (which compress at any bitrate) missed it.
 * 96kbps reduced but didn't eliminate it; 128kbps is still 6x smaller than the raw PCM this
 * replaced. (The other half of the original complaint — audible stutter — turned out to be a
 * separate playback-pipeline bug, not a codec/bitrate issue: see [ReaderPlayer.playAacFile].)
 */
object AacWriter {
    private const val BIT_RATE = 128_000
    private const val TIMEOUT_US = 10_000L

    fun write(file: File, audio: FloatArray, sampleRate: Int = 48000) {
        val pcm = ByteBuffer.allocate(audio.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (s in audio) putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }.array()

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        // configure()/start() must be inside the try — Android only has a small system-wide
        // pool of concurrent codec instances, so if either throws before the try begins,
        // release() below never runs and this instance leaks permanently. A leaked encoder
        // from one failure eventually exhausts the pool and makes every later attempt fail
        // too, turning one transient error into a permanent one.
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var codecStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            codecStarted = true

            val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false
            val bufferInfo = MediaCodec.BufferInfo()

            try {
                var inputOffset = 0
                var eosQueued = false
                var outputDone = false

                while (!outputDone) {
                    if (!eosQueued) {
                        val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val inBuf = codec.getInputBuffer(inIndex)!!
                            val remaining = pcm.size - inputOffset
                            // Round down to an even byte count — inBuf.capacity() is not
                            // guaranteed to be a multiple of 2, and splitting a 16-bit PCM
                            // sample across two separate queueInputBuffer calls corrupts the
                            // byte stream at that boundary. pcm.size is always even
                            // (audio.size * 2), and every chunk submitted here is now even
                            // too, so inputOffset/remaining stay even on every iteration —
                            // this can't drift back into an odd split.
                            val chunk = minOf(remaining, inBuf.capacity()).let { it - (it % 2) }
                            inBuf.clear()
                            if (chunk > 0) inBuf.put(pcm, inputOffset, chunk)
                            val isLast = inputOffset + chunk >= pcm.size
                            codec.queueInputBuffer(
                                inIndex, 0, chunk, 0,
                                if (isLast) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0,
                            )
                            inputOffset += chunk
                            if (isLast) eosQueued = true
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outIndex >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIndex)!!
                            val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (bufferInfo.size > 0 && muxerStarted && !isConfig) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            } finally {
                if (muxerStarted) muxer.stop()
                muxer.release()
            }
        } finally {
            if (codecStarted) codec.stop()
            codec.release()
        }
    }
}
