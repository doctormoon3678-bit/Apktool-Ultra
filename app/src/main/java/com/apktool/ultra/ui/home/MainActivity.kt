package com.apktool.ultra.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.apktool.ultra.R
import com.apktool.ultra.databinding.ActivityMainBinding
import com.apktool.ultra.ui.archive.ArchiveViewerActivity
import com.apktool.ultra.ui.decompile.DecompileActivity
import com.apktool.ultra.ui.disassembler.DisassemblerActivity
import com.apktool.ultra.ui.signer.SignerActivity
import com.apktool.ultra.ui.tools.ToolManagerActivity
import com.apktool.ultra.utils.FileUtils
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var pendingAction: String? = null

    private val pickApk = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleApkSelected(it) }
    }

    private val pickSoFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleSoSelected(it) }
    }

    private val pickSignApk = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleSignApkSelected(it) }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            pendingAction?.let { performAction(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "Apktool Ultra"
        supportActionBar?.subtitle = "Android Reverse Engineering Suite"

        setupMenuGrid()
        checkPermissions()

        intent?.data?.let { uri ->
            handleApkSelected(uri)
        }
    }

    private fun setupMenuGrid() {
        val menuItems = listOf(
            MenuItem("Decompile APK", "APK 디컴파일 & Smali 편집", R.drawable.ic_decompile, "decompile"),
            MenuItem("Archive Viewer", "APK 압축 내용 보기/편집", R.drawable.ic_archive, "archive"),
            MenuItem("Disassembler", "Ghidra - Native Lib 분석", R.drawable.ic_disassemble, "disassemble"),
            MenuItem("Sign APK", "APK 서명 도구", R.drawable.ic_sign, "sign"),
            MenuItem("Claude Code", "AI 코드 수정 도우미", R.drawable.ic_ai, "claude"),
            MenuItem("Tool Manager", "AAPT/Java 다운로드", R.drawable.ic_tools, "tools"),
            MenuItem("Recent Projects", "최근 작업 프로젝트", R.drawable.ic_recent, "recent")
        )

        binding.menuRecyclerView.layoutManager = GridLayoutManager(this, 2)
        binding.menuRecyclerView.adapter = MenuAdapter(menuItems) { item ->
            performAction(item.action)
        }
    }

    private fun performAction(action: String) {
        when (action) {
            "decompile" -> {
                pendingAction = action
                pickApk.launch("application/vnd.android.package-archive")
            }
            "archive" -> {
                pendingAction = action
                pickApk.launch("application/vnd.android.package-archive")
            }
            "disassemble" -> {
                pendingAction = action
                pickSoFile.launch("*/*")
            }
            "sign" -> {
                pendingAction = action
                pickSignApk.launch("application/vnd.android.package-archive")
            }
            "claude" -> {
                Toast.makeText(this, "코드 에디터에서 AI 버튼을 사용하세요", Toast.LENGTH_LONG).show()
            }
            "tools" -> {
                startActivity(Intent(this, ToolManagerActivity::class.java))
            }
            "recent" -> {
                showRecentProjects()
            }
        }
    }

    private fun handleApkSelected(uri: Uri) {
        val file = FileUtils.uriToFile(this, uri) ?: run {
            Toast.makeText(this, "Failed to read file", Toast.LENGTH_SHORT).show()
            return
        }

        when (pendingAction) {
            "decompile" -> {
                val intent = Intent(this, DecompileActivity::class.java)
                intent.putExtra("apk_path", file.absolutePath)
                startActivity(intent)
            }
            "archive" -> {
                val intent = Intent(this, ArchiveViewerActivity::class.java)
                intent.putExtra("apk_path", file.absolutePath)
                startActivity(intent)
            }
        }
    }

    private fun handleSoSelected(uri: Uri) {
        val file = FileUtils.uriToFile(this, uri) ?: run {
            Toast.makeText(this, "Failed to read file", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, DisassemblerActivity::class.java)
        intent.putExtra("so_path", file.absolutePath)
        startActivity(intent)
    }

    private fun handleSignApkSelected(uri: Uri) {
        val file = FileUtils.uriToFile(this, uri) ?: run {
            Toast.makeText(this, "Failed to read file", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, SignerActivity::class.java)
        intent.putExtra("apk_path", file.absolutePath)
        startActivity(intent)
    }

    private fun showRecentProjects() {
        Toast.makeText(this, "Recent projects coming soon", Toast.LENGTH_SHORT).show()
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        } else {
            val perms = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            if (perms.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
                permissionLauncher.launch(perms)
            }
        }
    }

    data class MenuItem(
        val title: String,
        val subtitle: String,
        val iconRes: Int,
        val action: String
    )
}
