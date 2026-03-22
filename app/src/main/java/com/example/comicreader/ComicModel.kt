package com.example.comicreader

import android.net.Uri

/**
 * Data class representing a comic book.
 *
 * @property uri The [Uri] pointing to the comic file (e.g., .cbz).
 * @property name The display name of the comic.
 * @property pageCount The total number of pages in the comic.
 * @property lastReadPage The index of the last page read by the user.
 * @property thumbnailUri The [Uri] of the thumbnail image for this comic.
 */
data class Comic(
    val uri: Uri,
    val name: String,
    val pageCount: Int = 0,
    val lastReadPage: Int = 0,
    val thumbnailUri: Uri? = null
)

/**
 * Data class representing a single page within a comic.
 *
 * @property entryName The name of the file entry within the comic archive.
 * @property pageIndex The index of the page in the reading order.
 */
data class ComicPage(
    val entryName: String,
    val pageIndex: Int
)