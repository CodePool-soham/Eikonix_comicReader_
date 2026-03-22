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

class ComicViewModel(application: Application) : AndroidViewModel(application) {
    private val _currentComicPages = MutableStateFlow<List<String>>(emptyList())
    val currentComicPages: StateFlow<List<String>> = _currentComicPages

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _currentUri = MutableStateFlow<Uri?>(null)
    val currentUri: StateFlow<Uri?> = _currentUri

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

    suspend fun getPageBitmap(uri: Uri, entryName: String): Bitmap? {
        return withContext(Dispatchers.IO) {
            ComicUtils.getPageBitmap(getApplication(), uri, entryName)
        }
    }
}