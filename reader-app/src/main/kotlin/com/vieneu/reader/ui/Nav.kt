package com.vieneu.reader.ui

object Routes {
    const val LIBRARY = "library"
    const val APP_SETTINGS = "app_settings"
    const val BOOK_DETAIL = "book/{bookId}"
    const val LISTEN = "listen/{bookId}/{chapterId}"
    const val SPEECH_SETTINGS = "speech_settings"
    const val BOOK_VOICE = "book_voice/{bookId}"

    fun bookDetail(bookId: Long) = "book/$bookId"
    fun listen(bookId: Long, chapterId: Long) = "listen/$bookId/$chapterId"
    fun bookVoice(bookId: Long) = "book_voice/$bookId"
}
