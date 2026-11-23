package com.apktool.ultra.ui.decompile

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.apktool.ultra.ApktoolUltraApp
import com.apktool.ultra.core.ApkCompiler
import com.apktool.ultra.core.ApkDecompiler
import com.apktool.ultra.databinding.ActivityDecompileBinding
import com.apktool.ultra.ui.editor.CodeEditorActivity
import kotlinx.coroutines.launch
import java.io.File

class DecompileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDecompileBinding
    private val decompiler = ApkDecompiler()
    private val compiler = ApkCompiler()
    private var projectDir: File? = null
    private var currentDir: File? = null
    private val pathStack = mutableListOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDecompileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressed() }

        val apkPath = intent.getStringExtra("apk_path") ?: run {
            finish()
            return
        }

        val apkFile = File(apkPath)
        supportActionBar?.title = "Decompile: ${apkFile.name}"

        binding.fileList.layoutManager = LinearLayoutManager(this)

        binding.btnRecompile.setOnClickListener { recompile() }

        decompile(apkFile)
    }

    private fun decompile(apkFile: File) {
        binding.progressBar.visibility = View.VISIBLE
        binding.statusText.text = "Decompiling ${apkFile.name}..."

        lifecycleScope.launch {
            val outputDir = File(ApktoolUltraApp.workDir, apkFile.nameWithoutExtension + "_decompiled")
            val result = decompiler.decompile(apkFile, outputDir)

            binding.progressBar.visibility = View.GONE

            if (result.success) {
                projectDir = result.outputDir
                binding.statusText.text = result.message
                binding.btnRecompile.visibility = View.VISIBLE
                navigateTo(result.outputDir!!)
            } else {
                binding.statusText.text = "Error: ${result.message}"
            }
        }
    }

    private fun navigateTo(dir: File) {
        currentDir = dir
        val files = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()

        val relativePath = projectDir?.let { dir.relativeTo(it).path } ?: dir.name
        binding.pathText.text = "/$relativePath"

        binding.fileList.adapter = FileListAdapter(files) { file ->
            if (file.isDirectory) {
                pathStack.add(dir)
                navigateTo(file)
            } else {
                openFile(file)
            }
        }
    }

    private fun openFile(file: File) {
        val intent = Intent(this, CodeEditorActivity::class.java)
        intent.putExtra("file_path", file.absolutePath)
        intent.putExtra("file_name", file.name)
        startActivity(intent)
    }

    private fun recompile() {
        val dir = projectDir ?: return
        binding.progressBar.visibility = View.VISIBLE
        binding.statusText.text = "Recompiling..."

        lifecycleScope.launch {
            val outputApk = File(ApktoolUltraApp.workDir, dir.name.replace("_decompiled", "_recompiled.apk"))
            val result = compiler.compile(dir, outputApk)

            binding.progressBar.visibility = View.GONE
            binding.statusText.text = result.message

            if (result.success) {
                Toast.makeText(this@DecompileActivity, "Build complete: ${outputApk.name}", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        if (pathStack.isNotEmpty()) {
            val prev = pathStack.removeAt(pathStack.size - 1)
            navigateTo(prev)
        } else {
            super.onBackPressed()
        }
    }
}
