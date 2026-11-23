package com.apktool.ultra.ui.tools

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.apktool.ultra.core.ToolDownloader
import com.apktool.ultra.databinding.ActivityToolManagerBinding
import kotlinx.coroutines.launch

class ToolManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityToolManagerBinding
    private val downloader = ToolDownloader()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityToolManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Tool Manager"
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.toolList.layoutManager = LinearLayoutManager(this)
        loadTools()
    }

    private fun loadTools() {
        val tools = downloader.getToolStatus()

        binding.toolList.adapter = ToolAdapter(tools) { tool ->
            if (tool.isInstalled()) {
                Toast.makeText(this, "${tool.displayName} is already installed", Toast.LENGTH_SHORT).show()
            } else {
                downloadTool(tool)
            }
        }
    }

    private fun downloadTool(tool: ToolDownloader.Tool) {
        binding.progressBar.visibility = View.VISIBLE
        binding.downloadStatus.text = "Downloading ${tool.displayName}..."
        binding.downloadProgress.visibility = View.VISIBLE
        binding.downloadProgress.progress = 0

        lifecycleScope.launch {
            val success = downloader.downloadTool(tool) { progress ->
                runOnUiThread {
                    if (progress.isComplete) {
                        binding.downloadStatus.text = "${tool.displayName} installed!"
                        binding.downloadProgress.progress = 100
                    } else if (progress.error != null) {
                        binding.downloadStatus.text = "Error: ${progress.error}"
                    } else {
                        binding.downloadProgress.progress = progress.percentage
                        binding.downloadStatus.text = "Downloading ${tool.displayName}... ${progress.percentage}%"
                    }
                }
            }

            binding.progressBar.visibility = View.GONE
            binding.downloadProgress.visibility = View.GONE

            if (success) {
                Toast.makeText(this@ToolManagerActivity, "${tool.displayName} installed", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@ToolManagerActivity, "Download failed", Toast.LENGTH_SHORT).show()
            }

            loadTools()
        }
    }
}
