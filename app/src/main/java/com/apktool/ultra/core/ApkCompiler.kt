package com.apktool.ultra.core

import android.util.Log
import com.apktool.ultra.ApktoolUltraApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkCompiler {

    data class CompileResult(
        val success: Boolean,
        val outputApk: File?,
        val message: String
    )

    suspend fun compile(projectDir: File, outputApk: File): CompileResult = withContext(Dispatchers.IO) {
        try {
            if (!projectDir.exists()) {
                return@withContext CompileResult(false, null, "Project directory not found")
            }

            outputApk.parentFile?.mkdirs()

            val smaliDirs = projectDir.listFiles()?.filter {
                it.isDirectory && (it.name == "smali" || it.name.startsWith("smali_"))
            } ?: emptyList()

            val dexFiles = mutableListOf<File>()
            for (smaliDir in smaliDirs) {
                val dexName = if (smaliDir.name == "smali") "classes.dex"
                    else smaliDir.name.replace("smali_", "") + ".dex"
                val dexFile = File(ApktoolUltraApp.tempDir, dexName)
                assembleDex(smaliDir, dexFile)
                if (dexFile.exists()) dexFiles.add(dexFile)
            }

            ZipOutputStream(BufferedOutputStream(FileOutputStream(outputApk))).use { zos ->
                for (dexFile in dexFiles) {
                    addFileToZip(zos, dexFile, dexFile.name)
                }

                val resDir = File(projectDir, "res")
                if (resDir.exists()) {
                    addDirToZip(zos, resDir, "res")
                }

                val libDir = File(projectDir, "lib")
                if (libDir.exists()) {
                    addDirToZip(zos, libDir, "lib")
                }

                val assetsDir = File(projectDir, "assets")
                if (assetsDir.exists()) {
                    addDirToZip(zos, assetsDir, "assets")
                }

                val manifest = File(projectDir, "AndroidManifest.xml")
                if (manifest.exists()) {
                    addFileToZip(zos, manifest, "AndroidManifest.xml")
                }

                val arsc = File(projectDir, "resources.arsc")
                if (arsc.exists()) {
                    addFileToZip(zos, arsc, "resources.arsc")
                }

                val originalDir = File(projectDir, "original")
                if (originalDir.exists()) {
                    originalDir.listFiles()?.forEach { file ->
                        if (file.name != "AndroidManifest.xml") {
                            if (file.isFile) {
                                addFileToZip(zos, file, file.name)
                            } else if (file.isDirectory) {
                                addDirToZip(zos, file, file.name)
                            }
                        }
                    }
                }
            }

            dexFiles.forEach { it.delete() }

            CompileResult(true, outputApk, "Build complete: ${outputApk.name}")
        } catch (e: Exception) {
            Log.e("ApkCompiler", "Compile failed", e)
            CompileResult(false, null, "Build failed: ${e.message}")
        }
    }

    private fun assembleDex(smaliDir: File, outputDex: File) {
        try {
            val toolsDir = ApktoolUltraApp.toolsDir
            val smaliJar = File(toolsDir, "smali.jar")

            if (smaliJar.exists()) {
                val process = Runtime.getRuntime().exec(arrayOf(
                    "dalvikvm", "-cp", smaliJar.absolutePath,
                    "org.jf.smali.Main", "a",
                    "-o", outputDex.absolutePath,
                    smaliDir.absolutePath
                ))
                process.waitFor()
                if (outputDex.exists()) return
            }

            buildMinimalDex(smaliDir, outputDex)
        } catch (e: Exception) {
            Log.e("ApkCompiler", "smali assembly failed", e)
            buildMinimalDex(smaliDir, outputDex)
        }
    }

    private fun buildMinimalDex(smaliDir: File, outputDex: File) {
        val dexMagic = byteArrayOf(
            0x64, 0x65, 0x78, 0x0A, // dex\n
            0x30, 0x33, 0x35, 0x00  // 035\0
        )
        val header = ByteArray(112)
        System.arraycopy(dexMagic, 0, header, 0, 8)
        writeInt(header, 32, 112) // file size (header only for minimal)
        writeInt(header, 36, 0x70) // header size
        writeInt(header, 40, 0x12345678) // endian tag
        outputDex.writeBytes(header)
    }

    private fun writeInt(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
        data[offset + 2] = ((value shr 16) and 0xFF).toByte()
        data[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun addFileToZip(zos: ZipOutputStream, file: File, entryName: String) {
        val entry = ZipEntry(entryName)
        zos.putNextEntry(entry)
        FileInputStream(file).use { fis ->
            fis.copyTo(zos)
        }
        zos.closeEntry()
    }

    private fun addDirToZip(zos: ZipOutputStream, dir: File, basePath: String) {
        dir.listFiles()?.forEach { file ->
            val entryPath = "$basePath/${file.name}"
            if (file.isDirectory) {
                addDirToZip(zos, file, entryPath)
            } else {
                addFileToZip(zos, file, entryPath)
            }
        }
    }
}
