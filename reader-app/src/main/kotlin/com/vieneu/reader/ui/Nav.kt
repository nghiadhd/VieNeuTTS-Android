package com.vieneu.reader.ui

object Routes {
    const val LIBRARY = "library"
    const val APP_SETTINGS = "app_settings"
    const val BOOK_DETAIL = "book/{bookId}"
    const val LISTEN = "listen/{bookId}/{chapterId}"
    const val BOOK_SETTINGS = "book_settings/{bookId}"
    const val BOOK_VOICE = "book_voice/{bookId}"

    fun bookDetail(bookId: Long) = "book/$bookId"
    fun listen(bookId: Long, chapterId: Long) = "listen/$bookId/$chapterId"
    fun bookSettings(bookId: Long) = "book_settings/$bookId"
    fun bookVoice(bookId: Long) = "book_voice/$bookId"
}
