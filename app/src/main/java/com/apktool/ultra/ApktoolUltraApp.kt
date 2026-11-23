package com.apktool.ultra

import android.app.Application
import android.os.Environment
import java.io.File

class ApktoolUltraApp : Application() {

    companion object {
        lateinit var instance: ApktoolUltraApp
            private set

        val workDir: File get() = File(instance.getExternalFilesDir(null), "workspace")
        val toolsDir: File get() = File(instance.getExternalFilesDir(null), "tools")
        val tempDir: File get() = File(instance.cacheDir, "temp")
        val keystoreDir: File get() = File(instance.getExternalFilesDir(null), "keystores")
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        workDir.mkdirs()
        toolsDir.mkdirs()
        tempDir.mkdirs()
        keystoreDir.mkdirs()
    }
}
