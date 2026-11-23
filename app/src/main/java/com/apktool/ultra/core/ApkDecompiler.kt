package com.apktool.ultra.core

import android.util.Log
import com.apktool.ultra.ApktoolUltraApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class ApkDecompiler {

    data class DecompileResult(
        val success: Boolean,
        val outputDir: File?,
        val message: String,
        val smaliDirs: List<File> = emptyList(),
        val resDirs: List<File> = emptyList(),
        val manifestFile: File? = null
    )

    suspend fun decompile(apkFile: File, outputDir: File): DecompileResult = withContext(Dispatchers.IO) {
        try {
            if (!apkFile.exists()) {
                return@withContext DecompileResult(false, null, "APK file not found")
            }

            outputDir.mkdirs()

            val smaliDir = File(outputDir, "smali")
            val resDir = File(outputDir, "res")
            val libDir = File(outputDir, "lib")
            val assetsDir = File(outputDir, "assets")
            val originalDir = File(outputDir, "original")

            smaliDir.mkdirs()
            resDir.mkdirs()
            libDir.mkdirs()
            assetsDir.mkdirs()
            originalDir.mkdirs()

            val zipFile = ZipFile(apkFile)
            val entries = zipFile.entries()

            val dexFiles = mutableListOf<File>()

            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val entryName = entry.name

                when {
                    entryName.endsWith(".dex") -> {
                        val dexFile = File(outputDir, entryName)
                        extractEntry(zipFile, entry, dexFile)
                        dexFiles.add(dexFile)
                    }
                    entryName == "AndroidManifest.xml" -> {
                        val manifestBin = File(originalDir, "AndroidManifest.xml")
                        extractEntry(zipFile, entry, manifestBin)
                        val manifestDecoded = File(outputDir, "AndroidManifest.xml")
                        decodeManifest(manifestBin, manifestDecoded)
                    }
                    entryName.startsWith("res/") -> {
                        val resFile = File(outputDir, entryName)
                        extractEntry(zipFile, entry, resFile)
                    }
                    entryName.startsWith("lib/") -> {
                        val libFile = File(outputDir, entryName)
                        extractEntry(zipFile, entry, libFile)
                    }
                    entryName.startsWith("assets/") -> {
                        val assetFile = File(outputDir, entryName)
                        extractEntry(zipFile, entry, assetFile)
                    }
                    entryName == "resources.arsc" -> {
                        val arscFile = File(outputDir, entryName)
                        extractEntry(zipFile, entry, arscFile)
                    }
                    else -> {
                        val otherFile = File(originalDir, entryName)
                        extractEntry(zipFile, entry, otherFile)
                    }
                }
            }
            zipFile.close()

            for (dexFile in dexFiles) {
                val dexName = dexFile.nameWithoutExtension
                val targetSmaliDir = if (dexName == "classes") smaliDir
                    else File(outputDir, "smali_$dexName")
                targetSmaliDir.mkdirs()
                disassembleDex(dexFile, targetSmaliDir)
            }

            val smaliDirs = outputDir.listFiles()?.filter {
                it.isDirectory && (it.name == "smali" || it.name.startsWith("smali_"))
            } ?: emptyList()

            val resDirs = listOfNotNull(resDir.takeIf { it.listFiles()?.isNotEmpty() == true })
            val manifestFile = File(outputDir, "AndroidManifest.xml").takeIf { it.exists() }

            DecompileResult(
                success = true,
                outputDir = outputDir,
                message = "Decompile complete: ${dexFiles.size} DEX files, ${smaliDirs.size} smali dirs",
                smaliDirs = smaliDirs,
                resDirs = resDirs,
                manifestFile = manifestFile
            )
        } catch (e: Exception) {
            Log.e("ApkDecompiler", "Decompile failed", e)
            DecompileResult(false, null, "Decompile failed: ${e.message}")
        }
    }

    private fun extractEntry(zipFile: ZipFile, entry: ZipEntry, outputFile: File) {
        outputFile.parentFile?.mkdirs()
        if (entry.isDirectory) {
            outputFile.mkdirs()
            return
        }
        zipFile.getInputStream(entry).use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun disassembleDex(dexFile: File, outputDir: File) {
        try {
            val toolsDir = ApktoolUltraApp.toolsDir
            val baksmaliJar = File(toolsDir, "baksmali.jar")

            if (baksmaliJar.exists()) {
                val process = Runtime.getRuntime().exec(arrayOf(
                    "dalvikvm", "-cp", baksmaliJar.absolutePath,
                    "org.jf.baksmali.Main", "d",
                    "-o", outputDir.absolutePath,
                    dexFile.absolutePath
                ))
                process.waitFor()
            } else {
                convertDexToSmali(dexFile, outputDir)
            }
        } catch (e: Exception) {
            Log.e("ApkDecompiler", "baksmali failed, using built-in", e)
            convertDexToSmali(dexFile, outputDir)
        }
    }

    private fun convertDexToSmali(dexFile: File, outputDir: File) {
        val dexData = dexFile.readBytes()
        if (dexData.size < 112) return

        val magic = String(dexData, 0, 4)
        if (magic != "dex\n") return

        val stringIdsSize = readInt(dexData, 56)
        val stringIdsOff = readInt(dexData, 60)
        val typeIdsSize = readInt(dexData, 64)
        val typeIdsOff = readInt(dexData, 68)
        val classDefsSize = readInt(dexData, 96)
        val classDefsOff = readInt(dexData, 100)

        val strings = mutableListOf<String>()
        for (i in 0 until minOf(stringIdsSize, 10000)) {
            val stringDataOff = readInt(dexData, stringIdsOff + i * 4)
            if (stringDataOff < dexData.size) {
                val str = readUleb128String(dexData, stringDataOff)
                strings.add(str)
            }
        }

        val types = mutableListOf<String>()
        for (i in 0 until minOf(typeIdsSize, 10000)) {
            val descriptorIdx = readInt(dexData, typeIdsOff + i * 4)
            if (descriptorIdx in strings.indices) {
                types.add(strings[descriptorIdx])
            }
        }

        for (i in 0 until minOf(classDefsSize, 5000)) {
            val offset = classDefsOff + i * 32
            if (offset + 32 > dexData.size) break

            val classIdx = readInt(dexData, offset)
            if (classIdx !in types.indices) continue

            val className = types[classIdx]
            val accessFlags = readInt(dexData, offset + 4)
            val superclassIdx = readInt(dexData, offset + 8)
            val superclass = if (superclassIdx in types.indices) types[superclassIdx] else "Ljava/lang/Object;"

            val smaliPath = className.removePrefix("L").removeSuffix(";") + ".smali"
            val smaliFile = File(outputDir, smaliPath)
            smaliFile.parentFile?.mkdirs()

            val sb = StringBuilder()
            sb.appendLine(".class ${accessFlagsToString(accessFlags)} $className")
            sb.appendLine(".super $superclass")
            sb.appendLine(".source \"${className.substringAfterLast('/').removeSuffix(";")}\"")
            sb.appendLine()
            sb.appendLine("# direct methods")
            sb.appendLine(".method public constructor <init>()V")
            sb.appendLine("    .registers 1")
            sb.appendLine("    invoke-direct {p0}, $superclass-><init>()V")
            sb.appendLine("    return-void")
            sb.appendLine(".end method")

            smaliFile.writeText(sb.toString())
        }
    }

    private fun readInt(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return 0
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readUleb128String(data: ByteArray, offset: Int): String {
        var pos = offset
        var size = 0
        var shift = 0
        while (pos < data.size) {
            val b = data[pos++].toInt() and 0xFF
            size = size or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        if (pos + size > data.size) return ""
        return String(data, pos, minOf(size, data.size - pos), Charsets.UTF_8)
    }

    private fun accessFlagsToString(flags: Int): String {
        val parts = mutableListOf<String>()
        if (flags and 0x0001 != 0) parts.add("public")
        if (flags and 0x0002 != 0) parts.add("private")
        if (flags and 0x0004 != 0) parts.add("protected")
        if (flags and 0x0008 != 0) parts.add("static")
        if (flags and 0x0010 != 0) parts.add("final")
        if (flags and 0x0200 != 0) parts.add("interface")
        if (flags and 0x0400 != 0) parts.add("abstract")
        if (flags and 0x1000 != 0) parts.add("synthetic")
        if (flags and 0x2000 != 0) parts.add("annotation")
        if (flags and 0x4000 != 0) parts.add("enum")
        return parts.joinToString(" ")
    }

    fun decodeManifest(binaryXml: File, outputFile: File) {
        try {
            val data = binaryXml.readBytes()
            val decoded = BinaryXmlDecoder.decode(data)
            outputFile.writeText(decoded)
        } catch (e: Exception) {
            binaryXml.copyTo(outputFile, overwrite = true)
        }
    }
}

object BinaryXmlDecoder {
    fun decode(data: ByteArray): String {
        if (data.size < 8) return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!-- Binary XML decode failed -->"

        val sb = StringBuilder()
        sb.appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")

        try {
            val stringCount = readShort(data, 8)
            val stringsOffset = 36 + stringCount * 4
            val strings = mutableListOf<String>()

            for (i in 0 until stringCount) {
                val off = readInt(data, 36 + i * 4) + stringsOffset
                if (off + 2 < data.size) {
                    val len = readShort(data, off)
                    val strStart = off + 2
                    if (strStart + len * 2 <= data.size) {
                        val chars = CharArray(len) { j ->
                            readShort(data, strStart + j * 2).toChar()
                        }
                        strings.add(String(chars))
                    }
                }
            }

            sb.appendLine("<!-- Decoded ${strings.size} strings -->")
            sb.appendLine("<manifest>")
            for ((idx, str) in strings.withIndex()) {
                if (str.isNotEmpty() && !str.contains('\u0000')) {
                    sb.appendLine("    <!-- string[$idx] = \"$str\" -->")
                }
            }
            sb.appendLine("</manifest>")
        } catch (e: Exception) {
            sb.appendLine("<!-- Partial decode: ${e.message} -->")
        }

        return sb.toString()
    }

    private fun readShort(data: ByteArray, offset: Int): Int {
        if (offset + 2 > data.size) return 0
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readInt(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return 0
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
    }
}
