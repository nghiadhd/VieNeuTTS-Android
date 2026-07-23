package com.vieneu.engine

import android.content.Context
import java.io.File
import uniffi.sea_g2p_android.SeaG2p

/**
 * Public API of the `:engine` module — this is what `:app-sample` (and later
 * the EPUB reader app) depends on. Only the phonemizer is wired up so far;
 * ONNX inference lands in a follow-up step.
 */
class TtsEngine private constructor(private val g2p: SeaG2p) {

    /** Mirrors `sea_g2p.SEAPipeline.run(text, punc_norm=True)` used by VieNeu-TTS's phonemizer. */
    fun phonemize(text: String): String = g2p.run(text, true)

    companion object {
        private const val DICT_ASSET = "sea_g2p.bin"

        /**
         * `sea_g2p.bin` is memory-mapped by the Rust core via a real file
         * path, so it has to be copied out of the read-only APK assets into
         * app-private storage once, then reused on every later launch.
         */
        fun create(context: Context): TtsEngine {
            val dictFile = File(context.filesDir, DICT_ASSET)
            if (!dictFile.exists() || dictFile.length() == 0L) {
                context.assets.open(DICT_ASSET).use { input ->
                    dictFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            return TtsEngine(SeaG2p(dictFile.absolutePath, "vi"))
        }
    }
}
