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
            "uber-apk-signer.jar",
            "APK 서명 도구 (uber-apk-signer)",
            "https://github.com/nickolay/uber-apk-signer/releases/download/1.3.0/uber-apk-signer-1.3.0.jar",
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
            "Java Runtime Environment (OpenJDK 17 aarch64)",
            "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.9%2B9/OpenJDK17U-jre_aarch64_linux_hotspot_17.0.9_9.tar.gz",
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

            val tempFile = File(outputFile.absolutePath + ".tmp")
            val success = downloadWithRedirects(tool.downloadUrl, tempFile, tool, onProgress)
            if (!success) {
                tempFile.delete()
                return@withContext false
            }

            if (tool.downloadUrl.endsWith(".tar.gz") || tool.downloadUrl.endsWith(".zip")) {
                extractArchive(tempFile, tool)
                tempFile.delete()
            } else {
                tempFile.renameTo(outputFile)
            }

            if (tool.fileName.endsWith(".jar")) {
                // JAR files are ready to use
            } else if (!tool.fileName.contains(".")) {
                outputFile.setExecutable(true)
            }

            onProgress(DownloadProgress(tool, 1, 1, true))
            true
        } catch (e: Exception) {
            Log.e("ToolDownloader", "Download failed for ${tool.displayName}", e)
            onProgress(DownloadProgress(tool, 0, 0, false, e.message))
            false
        }
    }

    private fun downloadWithRedirects(
        urlStr: String,
        outputFile: File,
        tool: Tool,
        onProgress: (DownloadProgress) -> Unit,
        maxRedirects: Int = 5
    ): Boolean {
        var currentUrl = urlStr
        var redirectCount = 0

        while (redirectCount < maxRedirects) {
            val url = URL(currentUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "ApktoolUltra/1.0")
            connection.setRequestProperty("Accept", "application/octet-stream")

            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                responseCode == HttpURLConnection.HTTP_SEE_OTHER ||
                responseCode == 307 || responseCode == 308) {
                val newUrl = connection.getHeaderField("Location")
                connection.disconnect()
                if (newUrl == null) {
                    onProgress(DownloadProgress(tool, 0, 0, false, "Redirect without Location header"))
                    return false
                }
                currentUrl = if (newUrl.startsWith("http")) newUrl else {
                    val base = URL(currentUrl)
                    URL(base, newUrl).toString()
                }
                redirectCount++
                continue
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                onProgress(DownloadProgress(tool, 0, 0, false, "HTTP $responseCode"))
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

        onProgress(DownloadProgress(tool, 0, 0, false, "Too many redirects"))
        return false
    }

    private fun extractArchive(archiveFile: File, tool: Tool) {
        val extractDir = File(ApktoolUltraApp.toolsDir, tool.fileName + "_extracted")
        extractDir.mkdirs()

        if (archiveFile.name.endsWith(".zip") || tool.downloadUrl.endsWith(".zip")) {
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
        } else if (tool.downloadUrl.endsWith(".tar.gz")) {
            extractTarGz(archiveFile, extractDir)
        }
    }

    private fun extractTarGz(archiveFile: File, extractDir: File) {
        try {
            val process = ProcessBuilder("tar", "xzf", archiveFile.absolutePath, "-C", extractDir.absolutePath)
                .redirectErrorStream(true)
                .start()
            process.waitFor()
        } catch (e: Exception) {
            Log.e("ToolDownloader", "tar extract failed, trying manual", e)
            val gzipInput = java.util.zip.GZIPInputStream(FileInputStream(archiveFile))
            val outFile = File(extractDir, archiveFile.name.removeSuffix(".gz"))
            FileOutputStream(outFile).use { output ->
                gzipInput.copyTo(output)
            }
            gzipInput.close()
        }
    }

    fun getToolStatus(): List<Pair<Tool, Boolean>> {
        return Tool.entries.map { it to it.isInstalled() }
    }

    fun getInstalledTools(): List<Tool> {
        return Tool.entries.filter { it.isInstalled() }
    }
}
