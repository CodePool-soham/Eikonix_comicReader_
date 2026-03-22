package com.example.comicreader

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for managing the state and logic related to a comic.
 *
 * This ViewModel handles loading comic pages and retrieving bitmaps for specific pages.
 *
 * @param application The [Application] context.
 */
class ComicViewModel(application: Application) : AndroidViewModel(application) {
    private val _currentComicPages = MutableStateFlow<List<String>>(emptyList())
    /**
     * A [StateFlow] emitting the list of entry names for the current comic's pages.
     */
    val currentComicPages: StateFlow<List<String>> = _currentComicPages

    private val _isLoading = MutableStateFlow(false)
    /**
     * A [StateFlow] emitting the loading state of the comic.
     */
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _currentUri = MutableStateFlow<Uri?>(null)
    /**
     * A [StateFlow] emitting the [Uri] of the currently loaded comic.
     */
    val currentUri: StateFlow<Uri?> = _currentUri

    /**
     * Loads the comic from the given [Uri].
     *
     * This function updates [isLoading] and [currentComicPages] state flows.
     *
     * @param uri The [Uri] of the comic file to load.
     */
    fun loadComic(uri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            _currentUri.value = uri
            val pages = withContext(Dispatchers.IO) {
                ComicUtils.getPagesFromCbz(getApplication(), uri)
            }
            _currentComicPages.value = pages
            _isLoading.value = false
        }
    }

    /**
     * Retrieves the [Bitmap] for a specific page in the comic.
     *
     * @param uri The [Uri] of the comic file.
     * @param entryName The name of the file entry for the page within the archive.
     * @return The [Bitmap] of the page, or null if it could not be retrieved.
     */
    suspend fun getPageBitmap(uri: Uri, entryName: String): Bitmap? {
        return withContext(Dispatchers.IO) {
            ComicUtils.getPageBitmap(getApplication(), uri, entryName)
        }
    }
}