package com.example.comicreader

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.util.LruCache
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
 * Optimized for performance and memory usage.
 */
class ComicViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "ComicViewModel"
    private val progressPrefs = application.getSharedPreferences("comic_progress", Context.MODE_PRIVATE)
    private val completedPrefs = application.getSharedPreferences("comic_completed", Context.MODE_PRIVATE)

    private val _currentComicPages = MutableStateFlow<List<String>>(emptyList())
    val currentComicPages: StateFlow<List<String>> = _currentComicPages

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _currentUri = MutableStateFlow<Uri?>(null)
    val currentUri: StateFlow<Uri?> = _currentUri

    private var cachedComicFile: File? = null
    private var cachedUri: Uri? = null

    // Simple LruCache for bitmaps to reduce CPU usage when flipping pages
    private val bitmapCache = object : LruCache<String, Bitmap>(3) {
        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: Bitmap?, newValue: Bitmap?) {
            // No explicit recycle here as Compose might still be using it during transition
        }
    }

    fun loadComic(uri: Uri) {
        if (cachedUri == uri && _currentComicPages.value.isNotEmpty()) return

        viewModelScope.launch {
            _isLoading.value = true
            _currentUri.value = uri
            
            val pages = withContext(Dispatchers.IO) {
                try {
                    ensureFileCached(uri)
                    val file = cachedComicFile
                    if (file != null && file.exists()) {
                        val name = file.name.lowercase()
                        if (name.endsWith(".cbr") || name.endsWith(".rar")) {
                            ComicUtils.getPagesFromCbr(file)
                        } else {
                            ComicUtils.getPagesFromZip(file)
                        }
                    } else {
                        emptyList()
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

    private fun ensureFileCached(uri: Uri) {
        if (cachedUri != uri || cachedComicFile == null || !cachedComicFile!!.exists()) {
            clearCache()
            val fileName = ComicUtils.getFileName(getApplication(), uri) ?: "temp.cbz"
            val suffix = if (fileName.lowercase().endsWith(".cbr")) ".cbr" else ".cbz"
            val tempFile = File(getApplication<Application>().cacheDir, "current_comic$suffix")
            try {
                ComicUtils.copyUriToFile(getApplication(), uri, tempFile)
                cachedComicFile = tempFile
                cachedUri = uri
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cache comic file", e)
            }
        }
    }

    suspend fun getPageBitmap(uri: Uri, entryName: String, reqWidth: Int = 0, reqHeight: Int = 0): Bitmap? {
        val cacheKey = "${uri}_${entryName}_${reqWidth}x${reqHeight}"
        bitmapCache.get(cacheKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                ensureFileCached(uri)
                val file = cachedComicFile
                if (file != null && file.exists()) {
                    val name = file.name.lowercase()
                    val bitmap = if (name.endsWith(".cbr") || name.endsWith(".rar")) {
                        ComicUtils.getPageBitmapFromCbr(file, entryName, reqWidth, reqHeight)
                    } else {
                        ComicUtils.getPageBitmapFromZip(file, entryName, reqWidth, reqHeight)
                    }
                    if (bitmap != null) {
                        bitmapCache.put(cacheKey, bitmap)
                    }
                    bitmap
                } else null
            } catch (e: Exception) {
                Log.e(TAG, "Error getting page bitmap", e)
                null
            }
        }
    }

    private fun clearCache() {
        cachedComicFile?.delete()
        cachedComicFile = null
        cachedUri = null
        bitmapCache.evictAll()
    }

    override fun onCleared() {
        super.onCleared()
        clearCache()
    }

    fun saveLastReadPage(uri: Uri, page: Int, totalPages: Int) {
        progressPrefs.edit().putInt(uri.toString(), page).apply()
        if (page >= totalPages - 1 && totalPages > 0) {
            setCompleted(uri, true)
        }
    }

    fun getLastReadPage(uri: Uri): Int {
        return progressPrefs.getInt(uri.toString(), 0)
    }

    fun setCompleted(uri: Uri, completed: Boolean) {
        completedPrefs.edit().putBoolean(uri.toString(), completed).apply()
    }

    fun isCompleted(uri: Uri): Boolean {
        return completedPrefs.getBoolean(uri.toString(), false)
    }

    fun resetProgress(uri: Uri) {
        progressPrefs.edit().remove(uri.toString()).apply()
        setCompleted(uri, false)
    }
}