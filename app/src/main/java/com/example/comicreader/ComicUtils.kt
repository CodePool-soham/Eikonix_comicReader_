package com.example.comicreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.util.regex.Pattern
import java.util.zip.ZipInputStream

object ComicUtils {
    private const val TAG = "ComicUtils"

    fun getPagesFromCbz(context: Context, uri: Uri): List<String> {
        val pages = mutableListOf<String>()
        try {
            Log.d(TAG, "Opening stream for pages: $uri")
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(BufferedInputStream(inputStream)).use { zipInputStream ->
                    var entry = zipInputStream.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && 
                            !entry.name.contains("__MACOSX", ignoreCase = true) && 
                            isImageFile(entry.name)) {
                            pages.add(entry.name)
                        }
                        zipInputStream.closeEntry()
                        entry = zipInputStream.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading CBZ pages from $uri", e)
        }
        
        val sortedPages = pages.sortedWith(NaturalOrderComparator())
        Log.d(TAG, "Found ${sortedPages.size} pages for $uri")
        return sortedPages
    }

    fun isImageFile(filename: String): Boolean {
        val lower = filename.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || 
               lower.endsWith(".png") || lower.endsWith(".webp") ||
               lower.endsWith(".bmp") || lower.endsWith(".gif")
    }

    fun getPageBitmap(context: Context, uri: Uri, entryName: String): Bitmap? {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(BufferedInputStream(inputStream)).use { zipInputStream ->
                    var entry = zipInputStream.nextEntry
                    while (entry != null) {
                        if (entry.name == entryName) {
                            val outputStream = ByteArrayOutputStream()
                            val buffer = ByteArray(65536) // Larger buffer for speed
                            var len: Int
                            while (zipInputStream.read(buffer).also { len = it } != -1) {
                                outputStream.write(buffer, 0, len)
                            }
                            val data = outputStream.toByteArray()
                            
                            val options = BitmapFactory.Options()
                            options.inPreferredConfig = Bitmap.Config.RGB_565
                            return BitmapFactory.decodeByteArray(data, 0, data.size, options)
                        }
                        zipInputStream.closeEntry()
                        entry = zipInputStream.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting page bitmap: $entryName from $uri", e)
        }
        return null
    }

    fun getThumbnail(context: Context, uri: Uri): Bitmap? {
        try {
            // Re-open for thumbnail specifically
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(BufferedInputStream(inputStream)).use { zipInputStream ->
                    var entry = zipInputStream.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && 
                            !entry.name.contains("__MACOSX", ignoreCase = true) && 
                            isImageFile(entry.name)) {
                            
                            val outputStream = ByteArrayOutputStream()
                            val buffer = ByteArray(32768)
                            var len: Int
                            while (zipInputStream.read(buffer).also { len = it } != -1) {
                                outputStream.write(buffer, 0, len)
                            }
                            val data = outputStream.toByteArray()
                            
                            val options = BitmapFactory.Options()
                            options.inSampleSize = 4
                            options.inPreferredConfig = Bitmap.Config.RGB_565
                            return BitmapFactory.decodeByteArray(data, 0, data.size, options)
                        }
                        zipInputStream.closeEntry()
                        entry = zipInputStream.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting thumbnail for $uri", e)
        }
        return null
    }
}

class NaturalOrderComparator : Comparator<String> {
    override fun compare(s1: String, s2: String): Int {
        val p = Pattern.compile("(\\d+)")
        val m1 = p.matcher(s1)
        val m2 = p.matcher(s2)

        var pos1 = 0
        var pos2 = 0

        while (m1.find(pos1) && m2.find(pos2)) {
            val s1Prefix = s1.substring(pos1, m1.start())
            val s2Prefix = s2.substring(pos2, m2.start())

            if (s1Prefix != s2Prefix) {
                return s1Prefix.compareTo(s2Prefix, ignoreCase = true)
            }

            val n1Str = m1.group()
            val n2Str = m2.group()
            
            try {
                val n1 = n1Str.toLong()
                val n2 = n2Str.toLong()
                if (n1 != n2) {
                    return n1.compareTo(n2)
                }
            } catch (e: NumberFormatException) {
                val res = n1Str.compareTo(n2Str)
                if (res != 0) return res
            }

            pos1 = m1.end()
            pos2 = m2.end()
        }

        return s1.substring(pos1).compareTo(s2.substring(pos2), ignoreCase = true)
    }
}
