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
import java.util.zip.ZipFile

/**
 * ViewModel for managing the state and logic related to a comic.
 * Optimized for performance and memory usage.
 */
class ComicViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "ComicViewModel"
    private val progressPrefs = application.getSharedPreferences("comic_progress", Context.MODE_PRIVATE)
    private val completedPrefs = application.getSharedPreferences("comic_completed", Context.MODE_PRIVATE)
    private val timestampPrefs = application.getSharedPreferences("comic_timestamps", Context.MODE_PRIVATE)

    private val _currentComicPages = MutableStateFlow<List<String>>(emptyList())
    val currentComicPages: StateFlow<List<String>> = _currentComicPages

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _currentUri = MutableStateFlow<Uri?>(null)
    val currentUri: StateFlow<Uri?> = _currentUri

    private var cachedComicFile: File? = null
    private var currentZipFile: ZipFile? = null
    private var cachedUri: Uri? = null

    // Bitmap cache: 1/8th of available memory
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8
    
    private val bitmapCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }

        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: Bitmap?, newValue: Bitmap?) {
            // Bitmaps are not recycled here to allow inBitmap reuse or if they are still being displayed
        }
    }

    // Reuse pool for inBitmap
    private val bitmapReusePool = mutableSetOf<Bitmap>()

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
                            // Close previous ZipFile if any
                            currentZipFile?.close()
                            currentZipFile = ZipFile(file)
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
            clearResources()
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
                    val inBitmap = bitmapReusePool.firstOrNull { it.isMutable && !it.isRecycled && !isBitmapInCache(it) }
                    
                    val bitmap = if (name.endsWith(".cbr") || name.endsWith(".rar")) {
                        ComicUtils.getPageBitmapFromCbr(file, entryName, reqWidth, reqHeight, inBitmap)
                    } else {
                        val zip = currentZipFile ?: ZipFile(file).also { currentZipFile = it }
                        ComicUtils.getPageBitmapFromZip(zip, entryName, reqWidth, reqHeight, inBitmap)
                    }

                    if (bitmap != null) {
                        bitmapCache.put(cacheKey, bitmap)
                        if (bitmap.isMutable) bitmapReusePool.add(bitmap)
                    }
                    bitmap
                } else null
            } catch (e: Exception) {
                Log.e(TAG, "Error getting page bitmap", e)
                null
            }
        }
    }

    private fun isBitmapInCache(bitmap: Bitmap): Boolean {
        val snapshot = bitmapCache.snapshot()
        return snapshot.values.contains(bitmap)
    }

    private fun clearResources() {
        currentZipFile?.close()
        currentZipFile = null
        cachedComicFile?.delete()
        cachedComicFile = null
        cachedUri = null
        bitmapCache.evictAll()
        bitmapReusePool.forEach { if (!it.isRecycled) it.recycle() }
        bitmapReusePool.clear()
    }

    override fun onCleared() {
        super.onCleared()
        clearResources()
    }

    fun saveLastReadPage(uri: Uri, page: Int, totalPages: Int) {
        progressPrefs.edit().putInt(uri.toString(), page).apply()
        timestampPrefs.edit().putLong(uri.toString(), System.currentTimeMillis()).apply()
        if (page >= totalPages - 1 && totalPages > 0) {
            setCompleted(uri, true)
        }
    }

    fun getLastReadPage(uri: Uri): Int {
        return progressPrefs.getInt(uri.toString(), 0)
    }

    fun getLastReadTime(uri: Uri): Long {
        return timestampPrefs.getLong(uri.toString(), 0L)
    }

    fun setCompleted(uri: Uri, completed: Boolean) {
        completedPrefs.edit().putBoolean(uri.toString(), completed).apply()
    }

    fun isCompleted(uri: Uri): Boolean {
        return completedPrefs.getBoolean(uri.toString(), false)
    }

    fun resetProgress(uri: Uri) {
        progressPrefs.edit().remove(uri.toString()).apply()
        timestampPrefs.edit().remove(uri.toString()).apply()
        setCompleted(uri, false)
    }
}