package com.example.comicreader

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ViewModel for managing the state and logic related to a comic.
 *
 * This ViewModel handles loading comic pages and retrieving bitmaps for specific pages.
 *
 * @param application The [Application] context.
 */
class ComicViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "ComicViewModel"
    private val progressPrefs = application.getSharedPreferences("comic_progress", Context.MODE_PRIVATE)
    private val completedPrefs = application.getSharedPreferences("comic_completed", Context.MODE_PRIVATE)

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

    private var cachedCbrFile: File? = null
    private var cachedCbrUri: Uri? = null

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
                try {
                    val fileName = ComicUtils.getFileName(getApplication(), uri) ?: ""
                    if (fileName.lowercase().endsWith(".cbr")) {
                        // For CBR, we cache the temp file to avoid repeated copies
                        if (cachedCbrUri != uri || cachedCbrFile == null || !cachedCbrFile!!.exists()) {
                            clearCachedCbr()
                            val tempFile = File(getApplication<Application>().cacheDir, "comic_${System.currentTimeMillis()}.cbr")
                            ComicUtils.copyUriToFile(getApplication(), uri, tempFile)
                            cachedCbrFile = tempFile
                            cachedCbrUri = uri
                        }
                        ComicUtils.getPagesFromCbr(cachedCbrFile!!)
                    } else {
                        ComicUtils.getPagesFromCbz(getApplication(), uri)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading comic pages", e)
                    emptyList<String>()
                }
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
            try {
                val fileName = ComicUtils.getFileName(getApplication(), uri) ?: ""
                if (fileName.lowercase().endsWith(".cbr")) {
                    if (cachedCbrUri == uri && cachedCbrFile != null && cachedCbrFile!!.exists()) {
                        ComicUtils.getPageBitmapFromCbr(cachedCbrFile!!, entryName)
                    } else {
                        // Fallback if not cached (should not happen in reader)
                        val tempFile = File(getApplication<Application>().cacheDir, "page_${System.currentTimeMillis()}.cbr")
                        ComicUtils.copyUriToFile(getApplication(), uri, tempFile)
                        val bitmap = ComicUtils.getPageBitmapFromCbr(tempFile, entryName)
                        tempFile.delete()
                        bitmap
                    }
                } else {
                    ComicUtils.getPageBitmapFromCbz(getApplication(), uri, entryName)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting page bitmap", e)
                null
            }
        }
    }

    private fun clearCachedCbr() {
        cachedCbrFile?.delete()
        cachedCbrFile = null
        cachedCbrUri = null
    }

    override fun onCleared() {
        super.onCleared()
        clearCachedCbr()
    }

    /**
     * Saves the last read page for a comic.
     */
    fun saveLastReadPage(uri: Uri, page: Int, totalPages: Int) {
        progressPrefs.edit().putInt(uri.toString(), page).apply()
        if (page == totalPages - 1 && totalPages > 0) {
            setCompleted(uri, true)
        }
    }

    /**
     * Gets the last read page for a comic.
     */
    fun getLastReadPage(uri: Uri): Int {
        return progressPrefs.getInt(uri.toString(), 0)
    }

    /**
     * Marks a comic as completed or not.
     */
    fun setCompleted(uri: Uri, completed: Boolean) {
        completedPrefs.edit().putBoolean(uri.toString(), completed).apply()
    }

    /**
     * Checks if a comic is completed.
     */
    fun isCompleted(uri: Uri): Boolean {
        return completedPrefs.getBoolean(uri.toString(), false)
    }

    /**
     * Resets progress for a comic.
     */
    fun resetProgress(uri: Uri) {
        progressPrefs.edit().remove(uri.toString()).apply()
        setCompleted(uri, false)
    }
}