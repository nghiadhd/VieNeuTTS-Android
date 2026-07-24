package com.vieneu.reader.data

/**
 * Hand-rolled JSON *writer* for the per-book/per-chapter recovery files (see
 * [BookRepository.reconcileFromDisk]) — no Gson/kotlinx.serialization dependency,
 * matching how `:engine`'s [com.vieneu.engine.tokenizer.MiniJson] avoids one for
 * parsing. Pure `String` in/out, no `File`/`Context`, so it's host-JVM testable.
 * Reading these files back uses `MiniJson.parse` directly (see BookRepository).
 */
object MetadataJson {
    fun bookMetadata(book: Book): String = buildString {
        append('{')
        append("\"folderId\":").append(jsonString(book.folderId)).append(',')
        append("\"title\":").append(jsonString(book.title)).append(',')
        append("\"author\":").append(jsonStringOrNull(book.author)).append(',')
        append("\"voiceOverride\":").append(jsonStringOrNull(book.voiceOverride)).append(',')
        append("\"addedAt\":").append(book.addedAt)
        append('}')
    }

    fun chapterMetadata(chapter: Chapter, sentences: List<Sentence>): String = buildString {
        append('{')
        append("\"title\":").append(jsonString(chapter.title)).append(',')
        append("\"sentenceCount\":").append(chapter.sentenceCount).append(',')
        append("\"sentences\":[")
        sentences.forEachIndexed { i, s ->
            if (i > 0) append(',')
            append('{')
            append("\"orderIndex\":").append(s.orderIndex).append(',')
            append("\"text\":").append(jsonString(s.text)).append(',')
            append("\"audioStatus\":").append(jsonString(s.audioStatus.name)).append(',')
            append("\"audioFileName\":").append(jsonStringOrNull(s.audioFilePath?.let { java.io.File(it).name } )).append(',')
            append("\"durationMs\":").append(s.durationMs?.toString() ?: "null")
            append('}')
        }
        append(']')
        append('}')
    }

    private fun jsonStringOrNull(s: String?): String = if (s == null) "null" else jsonString(s)

    private fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
