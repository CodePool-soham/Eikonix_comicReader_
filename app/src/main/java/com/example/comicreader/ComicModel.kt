package com.example.comicreader

import android.net.Uri

data class Comic(
    val uri: Uri,
    val name: String,
    val pageCount: Int = 0,
    val lastReadPage: Int = 0,
    val thumbnailUri: Uri? = null
)

data class ComicPage(
    val entryName: String,
    val pageIndex: Int
)