package com.apktool.ultra.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.util.zip.*

class ArchiveManager {

    data class ArchiveEntry(
        val name: String,
        val path: String,
        val size: Long,
        val compressedSize: Long,
        val isDirectory: Boolean,
        val crc: Long,
        val method: Int
    ) {
        val displaySize: String get() {
            return when {
                size < 1024 -> "$size B"
                size < 1024 * 1024 -> "${size / 1024} KB"
                else -> "${size / (1024 * 1024)} MB"
            }
        }

        val compressionRatio: String get() {
            if (size == 0L) return "0%"
            return "${((1.0 - compressedSize.toDouble() / size) * 100).toInt()}%"
        }

        val extension: String get() = name.substringAfterLast('.', "")

        val icon: String get() = when (extension.lowercase()) {
            "dex" -> "DEX"
            "xml" -> "XML"
            "png", "jpg", "jpeg", "webp" -> "IMG"
            "so" -> "LIB"
            "arsc" -> "RES"
            "smali" -> "SMI"
            "java" -> "JAV"
            "kt" -> "KT"
            "json" -> "JSN"
            "txt" -> "TXT"
            "mf", "sf", "rsa" -> "SIG"
            else -> "FILE"
        }
    }

    suspend fun listEntries(archiveFile: File): List<ArchiveEntry> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<ArchiveEntry>()
        ZipFile(archiveFile).use { zipFile ->
            val zipEntries = zipFile.entries()
            while (zipEntries.hasMoreElements()) {
                val entry = zipEntries.nextElement()
                entries.add(ArchiveEntry(
                    name = entry.name.substringAfterLast('/').ifEmpty { entry.name },
                    path = entry.name,
                    size = entry.size,
                    compressedSize = entry.compressedSize,
                    isDirectory = entry.isDirectory,
                    crc = entry.crc,
                    method = entry.method
                ))
            }
        }
        entries.sortedWith(compareBy({ !it.isDirectory }, { it.path }))
    }

    suspend fun listEntriesInDir(archiveFile: File, dirPath: String): List<ArchiveEntry> = withContext(Dispatchers.IO) {
        val allEntries = listEntries(archiveFile)
        val prefix = if (dirPath.isEmpty()) "" else if (dirPath.endsWith("/")) dirPath else "$dirPath/"
        val dirs = mutableSetOf<String>()
        val files = mutableListOf<ArchiveEntry>()

        for (entry in allEntries) {
            if (!entry.path.startsWith(prefix)) continue
            val relative = entry.path.removePrefix(prefix)
            if (relative.isEmpty()) continue

            if (relative.contains('/')) {
                val dirName = relative.substringBefore('/')
                if (dirs.add(dirName)) {
                    files.add(ArchiveEntry(
                        name = dirName,
                        path = "$prefix$dirName/",
                        size = 0,
                        compressedSize = 0,
                        isDirectory = true,
                        crc = 0,
                        method = 0
                    ))
                }
            } else {
                files.add(entry)
            }
        }

        files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    suspend fun extractEntry(archiveFile: File, entryPath: String, outputFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            ZipFile(archiveFile).use { zipFile ->
                val entry = zipFile.getEntry(entryPath) ?: return@withContext false
                outputFile.parentFile?.mkdirs()
                zipFile.getInputStream(entry).use { input ->
                    FileOutputStream(outputFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun extractAll(archiveFile: File, outputDir: File): Boolean = withContext(Dispatchers.IO) {
        try {
            outputDir.mkdirs()
            ZipFile(archiveFile).use { zipFile ->
                val entries = zipFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val outFile = File(outputDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        zipFile.getInputStream(entry).use { input ->
                            FileOutputStream(outFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun replaceEntry(archiveFile: File, entryPath: String, newFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(archiveFile.parent, "${archiveFile.name}.tmp")
            ZipFile(archiveFile).use { zipFile ->
                ZipOutputStream(BufferedOutputStream(FileOutputStream(tempFile))).use { zos ->
                    val entries = zipFile.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name == entryPath) {
                            val newEntry = ZipEntry(entryPath)
                            zos.putNextEntry(newEntry)
                            FileInputStream(newFile).use { it.copyTo(zos) }
                            zos.closeEntry()
                        } else {
                            zos.putNextEntry(ZipEntry(entry.name))
                            if (!entry.isDirectory) {
                                zipFile.getInputStream(entry).use { it.copyTo(zos) }
                            }
                            zos.closeEntry()
                        }
                    }
                }
            }
            archiveFile.delete()
            tempFile.renameTo(archiveFile)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun addEntry(archiveFile: File, entryPath: String, newFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(archiveFile.parent, "${archiveFile.name}.tmp")
            ZipFile(archiveFile).use { zipFile ->
                ZipOutputStream(BufferedOutputStream(FileOutputStream(tempFile))).use { zos ->
                    val entries = zipFile.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        zos.putNextEntry(ZipEntry(entry.name))
                        if (!entry.isDirectory) {
                            zipFile.getInputStream(entry).use { it.copyTo(zos) }
                        }
                        zos.closeEntry()
                    }
                    zos.putNextEntry(ZipEntry(entryPath))
                    FileInputStream(newFile).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            archiveFile.delete()
            tempFile.renameTo(archiveFile)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun deleteEntry(archiveFile: File, entryPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(archiveFile.parent, "${archiveFile.name}.tmp")
            ZipFile(archiveFile).use { zipFile ->
                ZipOutputStream(BufferedOutputStream(FileOutputStream(tempFile))).use { zos ->
                    val entries = zipFile.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name == entryPath) continue
                        zos.putNextEntry(ZipEntry(entry.name))
                        if (!entry.isDirectory) {
                            zipFile.getInputStream(entry).use { it.copyTo(zos) }
                        }
                        zos.closeEntry()
                    }
                }
            }
            archiveFile.delete()
            tempFile.renameTo(archiveFile)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun readEntryAsText(archiveFile: File, entryPath: String): String? = withContext(Dispatchers.IO) {
        try {
            ZipFile(archiveFile).use { zipFile ->
                val entry = zipFile.getEntry(entryPath) ?: return@withContext null
                zipFile.getInputStream(entry).bufferedReader().readText()
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun readEntryAsBytes(archiveFile: File, entryPath: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            ZipFile(archiveFile).use { zipFile ->
                val entry = zipFile.getEntry(entryPath) ?: return@withContext null
                zipFile.getInputStream(entry).readBytes()
            }
        } catch (e: Exception) {
            null
        }
    }
}
