package com.apktool.ultra.ui.archive

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.apktool.ultra.ApktoolUltraApp
import com.apktool.ultra.core.ArchiveManager
import com.apktool.ultra.databinding.ActivityArchiveViewerBinding
import com.apktool.ultra.ui.disassembler.DisassemblerActivity
import com.apktool.ultra.ui.editor.CodeEditorActivity
import com.apktool.ultra.utils.FileUtils
import kotlinx.coroutines.launch
import java.io.File

class ArchiveViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityArchiveViewerBinding
    private val archiveManager = ArchiveManager()
    private lateinit var apkFile: File
    private var currentPath = ""
    private val pathStack = mutableListOf<String>()
    private var replaceEntryPath: String? = null

    private val pickReplaceFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleReplaceFile(it) }
    }

    private val pickAddFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleAddFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArchiveViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { handleBack() }

        val apkPath = intent.getStringExtra("apk_path") ?: run { finish(); return }
        apkFile = File(apkPath)
        supportActionBar?.title = apkFile.name

        binding.entryList.layoutManager = LinearLayoutManager(this)

        binding.btnExtractAll.setOnClickListener { extractAll() }
        binding.btnAddFile.setOnClickListener { pickAddFile.launch("*/*") }
        binding.btnSave.setOnClickListener { saveArchive() }

        loadEntries()
    }

    private fun loadEntries() {
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            val entries = archiveManager.listEntriesInDir(apkFile, currentPath)
            binding.progressBar.visibility = View.GONE

            binding.pathText.text = if (currentPath.isEmpty()) "/" else "/$currentPath"
            binding.entryCount.text = "${entries.size} items"

            binding.entryList.adapter = ArchiveEntryAdapter(entries,
                onClick = { entry ->
                    if (entry.isDirectory) {
                        pathStack.add(currentPath)
                        currentPath = entry.path
                        loadEntries()
                    } else {
                        showEntryOptions(entry)
                    }
                },
                onLongClick = { entry ->
                    showEntryContextMenu(entry)
                }
            )
        }
    }

    private fun showEntryOptions(entry: ArchiveManager.ArchiveEntry) {
        val options = mutableListOf("Extract", "View Hex")

        if (entry.extension in listOf("smali", "java", "xml", "txt", "json", "kt", "properties", "mf", "sf")) {
            options.add(0, "Edit")
        }
        if (entry.extension == "so") {
            options.add(0, "Disassemble")
        }
        options.add("Replace")
        options.add("Delete")

        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Edit" -> editEntry(entry)
                    "Disassemble" -> disassembleEntry(entry)
                    "Extract" -> extractEntry(entry)
                    "View Hex" -> viewHex(entry)
                    "Replace" -> replaceEntry(entry)
                    "Delete" -> deleteEntry(entry)
                }
            }
            .show()
    }

    private fun showEntryContextMenu(entry: ArchiveManager.ArchiveEntry) {
        showEntryOptions(entry)
    }

    private fun editEntry(entry: ArchiveManager.ArchiveEntry) {
        lifecycleScope.launch {
            val tempFile = File(ApktoolUltraApp.tempDir, entry.name)
            if (archiveManager.extractEntry(apkFile, entry.path, tempFile)) {
                val intent = Intent(this@ArchiveViewerActivity, CodeEditorActivity::class.java)
                intent.putExtra("file_path", tempFile.absolutePath)
                intent.putExtra("file_name", entry.name)
                startActivity(intent)
            }
        }
    }

    private fun disassembleEntry(entry: ArchiveManager.ArchiveEntry) {
        lifecycleScope.launch {
            val tempFile = File(ApktoolUltraApp.tempDir, entry.name)
            if (archiveManager.extractEntry(apkFile, entry.path, tempFile)) {
                val intent = Intent(this@ArchiveViewerActivity, DisassemblerActivity::class.java)
                intent.putExtra("so_path", tempFile.absolutePath)
                startActivity(intent)
            }
        }
    }

    private fun extractEntry(entry: ArchiveManager.ArchiveEntry) {
        lifecycleScope.launch {
            val outputFile = File(ApktoolUltraApp.workDir, "extracted/${entry.name}")
            if (archiveManager.extractEntry(apkFile, entry.path, outputFile)) {
                Toast.makeText(this@ArchiveViewerActivity, "Extracted: ${outputFile.absolutePath}", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@ArchiveViewerActivity, "Extract failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun viewHex(entry: ArchiveManager.ArchiveEntry) {
        lifecycleScope.launch {
            val bytes = archiveManager.readEntryAsBytes(apkFile, entry.path)
            if (bytes != null) {
                val hexDump = FileUtils.formatHexDump(bytes, length = minOf(bytes.size, 4096))
                AlertDialog.Builder(this@ArchiveViewerActivity)
                    .setTitle("Hex: ${entry.name}")
                    .setMessage(hexDump)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun replaceEntry(entry: ArchiveManager.ArchiveEntry) {
        replaceEntryPath = entry.path
        pickReplaceFile.launch("*/*")
    }

    private fun handleReplaceFile(uri: Uri) {
        val path = replaceEntryPath ?: return
        val newFile = FileUtils.uriToFile(this, uri) ?: return

        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val success = archiveManager.replaceEntry(apkFile, path, newFile)
            binding.progressBar.visibility = View.GONE

            if (success) {
                Toast.makeText(this@ArchiveViewerActivity, "Replaced successfully", Toast.LENGTH_SHORT).show()
                loadEntries()
            } else {
                Toast.makeText(this@ArchiveViewerActivity, "Replace failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleAddFile(uri: Uri) {
        val newFile = FileUtils.uriToFile(this, uri) ?: return
        val entryPath = currentPath + newFile.name

        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val success = archiveManager.addEntry(apkFile, entryPath, newFile)
            binding.progressBar.visibility = View.GONE

            if (success) {
                Toast.makeText(this@ArchiveViewerActivity, "Added: $entryPath", Toast.LENGTH_SHORT).show()
                loadEntries()
            } else {
                Toast.makeText(this@ArchiveViewerActivity, "Add failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteEntry(entry: ArchiveManager.ArchiveEntry) {
        AlertDialog.Builder(this)
            .setTitle("Delete")
            .setMessage("Delete ${entry.name}?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val success = archiveManager.deleteEntry(apkFile, entry.path)
                    if (success) {
                        Toast.makeText(this@ArchiveViewerActivity, "Deleted", Toast.LENGTH_SHORT).show()
                        loadEntries()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun extractAll() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            val outputDir = File(ApktoolUltraApp.workDir, "${apkFile.nameWithoutExtension}_extracted")
            val success = archiveManager.extractAll(apkFile, outputDir)
            binding.progressBar.visibility = View.GONE

            if (success) {
                Toast.makeText(this@ArchiveViewerActivity, "Extracted to: ${outputDir.absolutePath}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveArchive() {
        Toast.makeText(this, "Archive saved: ${apkFile.name}", Toast.LENGTH_SHORT).show()
    }

    private fun handleBack() {
        if (pathStack.isNotEmpty()) {
            currentPath = pathStack.removeAt(pathStack.size - 1)
            loadEntries()
        } else {
            finish()
        }
    }

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        handleBack()
    }
}
