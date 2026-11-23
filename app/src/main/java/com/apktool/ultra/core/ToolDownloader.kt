package com.apktool.ultra.core

import android.util.Log
import com.apktool.ultra.ApktoolUltraApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

class ToolDownloader {

    enum class Tool(
        val displayName: String,
        val fileName: String,
        val description: String,
        val downloadUrl: String,
        val sizeEstimate: String
    ) {
        AAPT2(
            "AAPT2",
            "aapt2",
            "Android Asset Packaging Tool 2 - APK 리소스 컴파일 및 링크",
            "https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/8.2.0-10154469/aapt2-8.2.0-10154469-linux.jar",
            "~5 MB"
        ),
        BAKSMALI(
            "Baksmali",
            "baksmali.jar",
            "DEX to Smali 디스어셈블러",
            "https://bitbucket.org/JesusFreke/smali/downloads/baksmali-2.5.2.jar",
            "~1.5 MB"
        ),
        SMALI(
            "Smali",
            "smali.jar",
            "Smali to DEX 어셈블러",
            "https://bitbucket.org/JesusFreke/smali/downloads/smali-2.5.2.jar",
            "~1.5 MB"
        ),
        APKSIGNER(
            "ApkSigner",
            "apksigner.jar",
            "APK 서명 도구 (Google)",
            "https://dl.google.com/dl/android/maven2/com/android/tools/build/apksigner/8.2.0/apksigner-8.2.0.jar",
            "~1 MB"
        ),
        ZIPALIGN(
            "Zipalign",
            "zipalign",
            "APK ZIP 정렬 최적화 도구",
            "https://dl.google.com/android/repository/build-tools_r34-linux.zip",
            "~55 MB"
        ),
        JAVA_RUNTIME(
            "Java Runtime",
            "java",
            "Java Runtime Environment (Android용 OpenJDK)",
            "https://github.com/nickolay/termux-openjdk/releases/download/v17.0.1/openjdk-17.0.1-aarch64.tar.gz",
            "~45 MB"
        );

        fun isInstalled(): Boolean {
            val toolFile = File(ApktoolUltraApp.toolsDir, fileName)
            return toolFile.exists()
        }

        fun getInstalledFile(): File = File(ApktoolUltraApp.toolsDir, fileName)
    }

    data class DownloadProgress(
        val tool: Tool,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val isComplete: Boolean,
        val error: String? = null
    ) {
        val percentage: Int get() = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt() else 0
    }

    suspend fun downloadTool(
        tool: Tool,
        onProgress: (DownloadProgress) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val outputFile = File(ApktoolUltraApp.toolsDir, tool.fileName)
            outputFile.parentFile?.mkdirs()

            val url = URL(tool.downloadUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "ApktoolUltra/1.0")

            if (connection.responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                connection.responseCode == HttpURLConnection.HTTP_MOVED_PERM) {
                val newUrl = connection.getHeaderField("Location")
                connection.disconnect()
                return@withContext downloadFromUrl(URL(newUrl), outputFile, tool, onProgress)
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                onProgress(DownloadProgress(tool, 0, 0, false, "HTTP ${connection.responseCode}"))
                connection.disconnect()
                return@withContext false
            }

            val totalBytes = connection.contentLengthLong
            var downloaded = 0L

            connection.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(DownloadProgress(tool, downloaded, totalBytes, false))
                    }
                }
            }
            connection.disconnect()

            if (tool.fileName.endsWith(".jar")) {
                // JAR files are ready to use
            } else if (!tool.fileName.contains(".")) {
                outputFile.setExecutable(true)
            }

            if (tool.downloadUrl.endsWith(".tar.gz") || tool.downloadUrl.endsWith(".zip")) {
                extractArchive(outputFile, tool)
            }

            onProgress(DownloadProgress(tool, downloaded, totalBytes, true))
            true
        } catch (e: Exception) {
            Log.e("ToolDownloader", "Download failed for ${tool.displayName}", e)
            onProgress(DownloadProgress(tool, 0, 0, false, e.message))
            false
        }
    }

    private fun downloadFromUrl(
        url: URL,
        outputFile: File,
        tool: Tool,
        onProgress: (DownloadProgress) -> Unit
    ): Boolean {
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 30000
        connection.readTimeout = 30000

        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            return false
        }

        val totalBytes = connection.contentLengthLong
        var downloaded = 0L

        connection.inputStream.use { input ->
            FileOutputStream(outputFile).use { output ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    onProgress(DownloadProgress(tool, downloaded, totalBytes, false))
                }
            }
        }
        connection.disconnect()
        return true
    }

    private fun extractArchive(archiveFile: File, tool: Tool) {
        val extractDir = File(ApktoolUltraApp.toolsDir, tool.fileName + "_extracted")
        extractDir.mkdirs()

        if (archiveFile.name.endsWith(".zip")) {
            val zipFile = java.util.zip.ZipFile(archiveFile)
            val entries = zipFile.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val outFile = File(extractDir, entry.name)
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
            zipFile.close()
        }
    }

    fun getToolStatus(): List<Pair<Tool, Boolean>> {
        return Tool.entries.map { it to it.isInstalled() }
    }

    fun getInstalledTools(): List<Tool> {
        return Tool.entries.filter { it.isInstalled() }
    }
}
