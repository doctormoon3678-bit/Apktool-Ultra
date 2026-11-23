package com.apktool.ultra.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.*

object FileUtils {

    fun uriToFile(context: Context, uri: Uri): File? {
        return try {
            val fileName = getFileName(context, uri) ?: "temp_${System.currentTimeMillis()}"
            val tempFile = File(context.cacheDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            tempFile
        } catch (e: Exception) {
            null
        }
    }

    fun getFileName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        }
        return uri.lastPathSegment
    }

    fun getFileSize(file: File): String {
        val size = file.length()
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
            size < 1024 * 1024 * 1024 -> "%.1f MB".format(size / (1024.0 * 1024))
            else -> "%.2f GB".format(size / (1024.0 * 1024 * 1024))
        }
    }

    fun getFileExtension(file: File): String = file.extension.lowercase()

    fun isTextFile(file: File): Boolean {
        val textExts = setOf(
            "txt", "xml", "json", "smali", "java", "kt", "py", "js", "html",
            "css", "md", "yml", "yaml", "properties", "gradle", "pro", "cfg",
            "ini", "sh", "bat", "mf", "sf", "csv", "log"
        )
        return file.extension.lowercase() in textExts
    }

    fun isImageFile(file: File): Boolean {
        val imageExts = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg")
        return file.extension.lowercase() in imageExts
    }

    fun isNativeLib(file: File): Boolean = file.extension.lowercase() == "so"

    fun isDexFile(file: File): Boolean = file.extension.lowercase() == "dex"

    fun copyFile(src: File, dst: File): Boolean {
        return try {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun deleteRecursive(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursive(it) }
        }
        return file.delete()
    }

    fun formatHexDump(data: ByteArray, offset: Int = 0, length: Int = data.size, baseAddr: Long = 0): String {
        val sb = StringBuilder()
        val end = minOf(offset + length, data.size)
        var pos = offset

        while (pos < end) {
            sb.append("%08X  ".format(baseAddr + pos - offset))

            val lineEnd = minOf(pos + 16, end)
            for (i in pos until pos + 16) {
                if (i < lineEnd) {
                    sb.append("%02X ".format(data[i].toInt() and 0xFF))
                } else {
                    sb.append("   ")
                }
                if ((i - pos) == 7) sb.append(" ")
            }

            sb.append(" |")
            for (i in pos until lineEnd) {
                val c = data[i].toInt() and 0xFF
                sb.append(if (c in 32..126) c.toChar() else '.')
            }
            sb.appendLine("|")

            pos += 16
        }
        return sb.toString()
    }
}
