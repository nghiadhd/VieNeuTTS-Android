package com.vieneu.reader.epub

import java.io.InputStream
import nl.siegmann.epublib.domain.TOCReference
import nl.siegmann.epublib.epub.EpubReader
import org.jsoup.Jsoup

data class ParsedChapter(val title: String, val plainText: String)
data class ParsedBook(val title: String, val author: String?, val chapters: List<ParsedChapter>)

/**
 * Extracts reading-order chapters (title + plain text) from an .epub. Uses
 * `epublib` for spine/TOC/resource parsing (an EPUB is a zip of XHTML +
 * OPF/NCX; hand-rolling that parsing isn't worth it) and Jsoup to turn each
 * chapter's XHTML into plain text for the TTS/sentence-splitting pipeline.
 */
object EpubParser {
    fun parse(inputStream: InputStream): ParsedBook {
        val book = EpubReader().readEpub(inputStream)

        val tocTitleByHref = mutableMapOf<String, String>()
        fun walkToc(refs: List<TOCReference>) {
            for (ref in refs) {
                val href = ref.resource?.href
                if (href != null && ref.title.isNotBlank()) tocTitleByHref[href] = ref.title
                walkToc(ref.children)
            }
        }
        walkToc(book.tableOfContents.tocReferences)

        val chapters = mutableListOf<ParsedChapter>()
        for ((index, spineRef) in book.spine.spineReferences.withIndex()) {
            val resource = spineRef.resource ?: continue
            val html = resource.reader.use { it.readText() }
            val doc = Jsoup.parse(html)

            // A heading that duplicates the chapter title shouldn't also be
            // read out as the first sentence of the body — resolve the title
            // first, then drop that exact heading element before extracting text.
            val headingEl = doc.select("h1, h2, h3").firstOrNull()
            val title = tocTitleByHref[resource.href]
                ?: headingEl?.text()?.takeIf { it.isNotBlank() }
                ?: "Chương ${index + 1}"
            if (headingEl != null && headingEl.text().trim() == title.trim()) headingEl.remove()

            val plainText = (doc.body()?.text() ?: "").trim()
            if (plainText.isBlank()) continue // skip cover pages / empty spine entries

            chapters.add(ParsedChapter(title.trim(), plainText))
        }

        val author = book.metadata.authors.firstOrNull()
            ?.let { listOfNotNull(it.firstname, it.lastname).joinToString(" ").trim() }
            ?.takeIf { it.isNotBlank() }

        return ParsedBook(title = book.title ?: "Untitled", author = author, chapters = chapters)
    }
}
