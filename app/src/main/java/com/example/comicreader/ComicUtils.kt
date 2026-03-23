package com.example.comicreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.github.junrar.Archive
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.regex.Pattern
import java.util.zip.ZipInputStream

/**
 * Utility object for comic book related operations, such as reading .cbz and .cbr files.
 */
object ComicUtils {
    private const val TAG = "ComicUtils"

    /**
     * Extracts a list of entry names representing pages from a comic file (.cbz or .cbr).
     *
     * @param context The [Context] for accessing the content resolver.
     * @param uri The [Uri] of the comic file.
     * @return A list of entry names, sorted in natural order.
     */
    fun getPages(context: Context, uri: Uri): List<String> {
        val fileName = getFileName(context, uri) ?: ""
        return if (fileName.lowercase().endsWith(".cbr")) {
            val tempFile = File(context.cacheDir, "temp_pages_${System.currentTimeMillis()}.cbr")
            try {
                copyUriToFile(context, uri, tempFile)
                getPagesFromCbr(tempFile)
            } finally {
                tempFile.delete()
            }
        } else {
            getPagesFromCbz(context, uri)
        }
    }

    /**
     * Extracts a list of entry names representing pages from a .cbz file.
     */
    fun getPagesFromCbz(context: Context, uri: Uri): List<String> {
        val pages = mutableListOf<String>()
        try {
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

        return pages.sortedWith(NaturalOrderComparator())
    }

    /**
     * Extracts a list of entry names representing pages from a .cbr file.
     */
    fun getPagesFromCbr(file: File): List<String> {
        val pages = mutableListOf<String>()
        try {
            Archive(file).use { archive ->
                for (header in archive.fileHeaders) {
                    if (!header.isDirectory &&
                        !header.fileNameString.contains("__MACOSX", ignoreCase = true) &&
                        isImageFile(header.fileNameString)) {
                        pages.add(header.fileNameString)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading CBR pages from ${file.path}", e)
        }
        return pages.sortedWith(NaturalOrderComparator())
    }

    /**
     * Checks if a filename corresponds to an image file.
     *
     * @param filename The name of the file to check.
     * @return True if the file has an image extension, false otherwise.
     */
    fun isImageFile(filename: String): Boolean {
        val lower = filename.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || 
               lower.endsWith(".png") || lower.endsWith(".webp") ||
               lower.endsWith(".bmp") || lower.endsWith(".gif")
    }

    /**
     * Retrieves the [Bitmap] for a specific entry name within a comic file.
     *
     * @param context The [Context] for accessing the content resolver.
     * @param uri The [Uri] of the comic file.
     * @param entryName The name of the file entry for the page within the archive.
     * @return The [Bitmap] of the page, or null if it could not be retrieved.
     */
    fun getPageBitmap(context: Context, uri: Uri, entryName: String): Bitmap? {
        val fileName = getFileName(context, uri) ?: ""
        return if (fileName.lowercase().endsWith(".cbr")) {
            val tempFile = File(context.cacheDir, "temp_bitmap_${System.currentTimeMillis()}.cbr")
            try {
                copyUriToFile(context, uri, tempFile)
                getPageBitmapFromCbr(tempFile, entryName)
            } finally {
                tempFile.delete()
            }
        } else {
            getPageBitmapFromCbz(context, uri, entryName)
        }
    }

    /**
     * Retrieves the [Bitmap] for a specific entry name within a .cbz file.
     */
    fun getPageBitmapFromCbz(context: Context, uri: Uri, entryName: String): Bitmap? {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(BufferedInputStream(inputStream)).use { zipInputStream ->
                    var entry = zipInputStream.nextEntry
                    while (entry != null) {
                        if (entry.name == entryName) {
                            val outputStream = ByteArrayOutputStream()
                            val buffer = ByteArray(65536)
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
            Log.e(TAG, "Error getting CBZ page bitmap: $entryName from $uri", e)
        }
        return null
    }

    /**
     * Retrieves the [Bitmap] for a specific entry name within a .cbr file.
     */
    fun getPageBitmapFromCbr(file: File, entryName: String): Bitmap? {
        try {
            Archive(file).use { archive ->
                val header = archive.fileHeaders.find { it.fileNameString == entryName }
                if (header != null) {
                    val outputStream = ByteArrayOutputStream()
                    archive.extractFile(header, outputStream)
                    val data = outputStream.toByteArray()

                    val options = BitmapFactory.Options()
                    options.inPreferredConfig = Bitmap.Config.RGB_565
                    return BitmapFactory.decodeByteArray(data, 0, data.size, options)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting CBR page bitmap: $entryName from ${file.path}", e)
        }
        return null
    }

    /**
     * Gets a cached thumbnail file for a comic, or extracts it if not cached.
     *
     * @param context The [Context] for accessing folders.
     * @param uri The [Uri] of the comic file.
     * @return The [File] pointing to the thumbnail, or null if it could not be generated.
     */
    fun getThumbnailFile(context: Context, uri: Uri): File? {
        val cacheDir = File(context.cacheDir, "thumbnails")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        val fileNameHash = md5(uri.toString())
        val thumbnailFile = File(cacheDir, "$fileNameHash.jpg")

        if (thumbnailFile.exists()) {
            return thumbnailFile
        }

        // Not cached, extract it
        try {
            val pages = getPages(context, uri)
            if (pages.isNotEmpty()) {
                val firstPage = pages.first()
                val bitmap = getPageBitmap(context, uri, firstPage)
                bitmap?.let {
                    val thumb = Bitmap.createScaledBitmap(it, it.width / 4, it.height / 4, true)
                    FileOutputStream(thumbnailFile).use { out ->
                        thumb.compress(Bitmap.CompressFormat.JPEG, 80, out)
                    }
                    it.recycle()
                    thumb.recycle()
                    return thumbnailFile
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating thumbnail for $uri", e)
        }
        return null
    }

    private fun md5(s: String): String {
        val digest = MessageDigest.getInstance("MD5")
        digest.update(s.toByteArray())
        val messageDigest = digest.digest()
        val hexString = StringBuilder()
        for (aMessageDigest in messageDigest) {
            var h = Integer.toHexString(0xFF and aMessageDigest.toInt())
            while (h.length < 2) h = "0$h"
            hexString.append(h)
        }
        return hexString.toString()
    }

    /**
     * Helper to get the filename from a [Uri].
     */
    fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    /**
     * Copies the content from a [Uri] to a local [File].
     */
    fun copyUriToFile(context: Context, uri: Uri, file: File) {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(file).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
    }
}

/**
 * A [Comparator] for strings that sorts them in natural order (e.g., "page 2" before "page 10").
 */
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