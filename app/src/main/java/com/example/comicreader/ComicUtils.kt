package com.example.comicreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.github.junrar.Archive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.regex.Pattern
import java.util.zip.ZipFile

/**
 * Utility object for comic book related operations, such as reading .cbz and .cbr files.
 * Optimized for performance and memory efficiency.
 */
object ComicUtils {
    private const val TAG = "ComicUtils"

    /**
     * Extracts a list of entry names representing pages from a comic file (.cbz or .cbr).
     */
    fun getPages(file: File): List<String> {
        val fileName = file.name.lowercase()
        return try {
            if (fileName.endsWith(".cbr") || fileName.endsWith(".rar")) {
                getPagesFromCbr(file)
            } else {
                getPagesFromZip(file)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting pages", e)
            emptyList()
        }
    }

    fun getPagesFromZip(file: File): List<String> {
        val pages = mutableListOf<String>()
        try {
            ZipFile(file).use { zipFile ->
                val entries = zipFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory &&
                        !entry.name.contains("__MACOSX", ignoreCase = true) &&
                        isImageFile(entry.name)) {
                        pages.add(entry.name)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading ZIP pages from ${file.path}", e)
        }
        return pages.sortedWith(NaturalOrderComparator())
    }

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

    fun isImageFile(filename: String): Boolean {
        val lower = filename.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || 
               lower.endsWith(".png") || lower.endsWith(".webp") ||
               lower.endsWith(".bmp") || lower.endsWith(".gif")
    }

    fun isArchiveFile(filename: String?): Boolean {
        val lower = filename?.lowercase() ?: return false
        return lower.endsWith(".cbz") || lower.endsWith(".zip") || 
               lower.endsWith(".cbr") || lower.endsWith(".rar")
    }

    fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (reqWidth > 0 && reqHeight > 0) {
            if (height > reqHeight || width > reqWidth) {
                val halfHeight: Int = height / 2
                val halfWidth: Int = width / 2
                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }
        }
        return inSampleSize
    }

    /**
     * Decodes a bitmap from a ZipFile entry with optimized memory usage.
     */
    fun getPageBitmapFromZip(zipFile: ZipFile, entryName: String, reqWidth: Int = 0, reqHeight: Int = 0, inBitmap: Bitmap? = null): Bitmap? {
        try {
            val entry = zipFile.getEntry(entryName) ?: return null
            
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            zipFile.getInputStream(entry).use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565
            
            // Enable bitmap reuse if possible
            if (inBitmap != null && canUseForInBitmap(inBitmap, options)) {
                options.inBitmap = inBitmap
                options.inMutable = true
            }

            return zipFile.getInputStream(entry).use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding ZIP entry: $entryName", e)
        }
        return null
    }

    /**
     * Decodes a bitmap from a CBR archive with optimized memory usage.
     */
    fun getPageBitmapFromCbr(file: File, entryName: String, reqWidth: Int = 0, reqHeight: Int = 0, inBitmap: Bitmap? = null): Bitmap? {
        try {
            Archive(file).use { archive ->
                val header = archive.fileHeaders.find { it.fileNameString == entryName } ?: return null
                val outputStream = ByteArrayOutputStream()
                archive.extractFile(header, outputStream)
                val data = outputStream.toByteArray()

                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeByteArray(data, 0, data.size, options)

                options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
                options.inJustDecodeBounds = false
                options.inPreferredConfig = Bitmap.Config.RGB_565

                if (inBitmap != null && canUseForInBitmap(inBitmap, options)) {
                    options.inBitmap = inBitmap
                    options.inMutable = true
                }

                return BitmapFactory.decodeByteArray(data, 0, data.size, options)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding CBR entry: $entryName", e)
        }
        return null
    }

    private fun canUseForInBitmap(candidate: Bitmap, targetOptions: BitmapFactory.Options): Boolean {
        val width = targetOptions.outWidth / targetOptions.inSampleSize
        val height = targetOptions.outHeight / targetOptions.inSampleSize
        // For RGB_565, it's 2 bytes per pixel
        val byteCount = width * height * 2
        return candidate.isMutable && !candidate.isRecycled && candidate.allocationByteCount >= byteCount
    }

    fun getThumbnailFile(context: Context, uri: Uri): File? {
        val cacheDir = File(context.cacheDir, "thumbnails")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        val fileNameHash = md5(uri.toString())
        val thumbnailFile = File(cacheDir, "$fileNameHash.webp")

        if (thumbnailFile.exists()) return thumbnailFile

        try {
            val fileName = getFileName(context, uri)?.lowercase() ?: ""
            val isRar = fileName.endsWith(".cbr") || fileName.endsWith(".rar")
            
            val tempFile = File(context.cacheDir, "thumb_extract_${System.currentTimeMillis()}")
            copyUriToFile(context, uri, tempFile)

            val pages = if (isRar) getPagesFromCbr(tempFile) else getPagesFromZip(tempFile)
            if (pages.isNotEmpty()) {
                val firstPage = pages.first()
                val bitmap = if (isRar) {
                    getPageBitmapFromCbr(tempFile, firstPage, 300, 450)
                } else {
                    ZipFile(tempFile).use { zipFile ->
                        getPageBitmapFromZip(zipFile, firstPage, 300, 450)
                    }
                }

                bitmap?.let {
                    FileOutputStream(thumbnailFile).use { out ->
                        it.compress(Bitmap.CompressFormat.WEBP, 85, out)
                    }
                    it.recycle()
                    tempFile.delete()
                    return thumbnailFile
                }
            }
            tempFile.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Error generating thumbnail", e)
        }
        return null
    }

    fun scanDirectory(context: Context, treeUri: Uri, results: MutableList<Pair<Uri, String>>) {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        scanDirectoryRecursive(context, treeUri, rootId, results)
    }

    private fun scanDirectoryRecursive(context: Context, rootUri: Uri, parentDocumentId: String, results: MutableList<Pair<Uri, String>>) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, parentDocumentId)
        try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idCol)
                    val name = cursor.getString(nameCol)
                    val mime = cursor.getString(mimeCol)
                    
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        scanDirectoryRecursive(context, rootUri, id, results)
                    } else if (isArchiveFile(name)) {
                        results.add(DocumentsContract.buildDocumentUriUsingTree(rootUri, id) to name)
                    }
                }
            }
        } catch (e: Exception) {}
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

    fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = cursor.getString(index)
                }
            }
        }
        if (result == null) {
            result = uri.path?.substringAfterLast('/')
        }
        return result
    }

    fun copyUriToFile(context: Context, uri: Uri, file: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }
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
            if (s1Prefix != s2Prefix) return s1Prefix.compareTo(s2Prefix, ignoreCase = true)
            val n1 = m1.group().toLongOrNull() ?: 0L
            val n2 = m2.group().toLongOrNull() ?: 0L
            if (n1 != n2) return n1.compareTo(n2)
            pos1 = m1.end()
            pos2 = m2.end()
        }
        return s1.substring(pos1).compareTo(s2.substring(pos2), ignoreCase = true)
    }
}
