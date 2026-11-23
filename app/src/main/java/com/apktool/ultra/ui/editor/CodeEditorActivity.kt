package com.apktool.ultra.ui.editor

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.apktool.ultra.R
import com.apktool.ultra.core.ClaudeApiClient
import com.apktool.ultra.databinding.ActivityCodeEditorBinding
import com.apktool.ultra.utils.SyntaxHighlighter
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import java.io.File
import java.util.LinkedList

class CodeEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCodeEditorBinding
    private lateinit var claudeClient: ClaudeApiClient
    private var filePath: String = ""
    private var isModified = false
    private val undoStack = LinkedList<UndoItem>()
    private val redoStack = LinkedList<UndoItem>()
    private var ignoreTextChange = false

    data class UndoItem(val start: Int, val before: String, val after: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCodeEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        claudeClient = ClaudeApiClient(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { handleBack() }

        filePath = intent.getStringExtra("file_path") ?: run { finish(); return }
        val fileName = intent.getStringExtra("file_name") ?: File(filePath).name
        supportActionBar?.title = fileName

        loadFile()
        setupEditor()
    }

    private fun loadFile() {
        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val content = file.readText()
        ignoreTextChange = true
        binding.codeEditor.setText(content)
        ignoreTextChange = false
        applyHighlighting()
        updateLineNumbers()
        isModified = false
        updateTitle()
    }

    private fun setupEditor() {
        binding.codeEditor.addTextChangedListener(object : TextWatcher {
            private var beforeText = ""
            private var start = 0

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (ignoreTextChange) return
                this.start = start
                this.beforeText = s?.substring(start, start + count) ?: ""
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (ignoreTextChange) return
                val afterText = s?.substring(start, start + count) ?: ""
                undoStack.push(UndoItem(start, beforeText, afterText))
                redoStack.clear()
                isModified = true
                updateTitle()
            }

            override fun afterTextChanged(s: Editable?) {
                if (!ignoreTextChange) {
                    updateLineNumbers()
                }
            }
        })

        binding.codeEditor.setHorizontallyScrolling(true)
        binding.codeEditor.textSize = 13f

        binding.btnUndo.setOnClickListener { undo() }
        binding.btnRedo.setOnClickListener { redo() }
        binding.btnSave.setOnClickListener { saveFile() }
        binding.btnSearch.setOnClickListener { showSearchDialog() }
        binding.btnAi.setOnClickListener { onAiButtonClicked() }
    }

    private fun onAiButtonClicked() {
        if (!claudeClient.isConfigured) {
            showApiKeyDialog {
                if (claudeClient.isConfigured) {
                    showClaudeChatDialog()
                }
            }
        } else {
            showClaudeChatDialog()
        }
    }

    private fun showApiKeyDialog(onSaved: (() -> Unit)? = null) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_api_key, null)
        val etApiKey = dialogView.findViewById<TextInputEditText>(R.id.etApiKey)
        etApiKey.setText(claudeClient.apiKey)

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("저장") { _, _ ->
                val key = etApiKey.text?.toString()?.trim() ?: ""
                if (key.isNotEmpty()) {
                    claudeClient.apiKey = key
                    Toast.makeText(this, "API 키가 저장되었습니다", Toast.LENGTH_SHORT).show()
                    onSaved?.invoke()
                } else {
                    Toast.makeText(this, "API 키를 입력해주세요", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showClaudeChatDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_claude_chat, null)
        val etPrompt = dialogView.findViewById<EditText>(R.id.etPrompt)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)
        val btnSend = dialogView.findViewById<MaterialButton>(R.id.btnSend)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressBar)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tvStatus)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSend.setOnClickListener {
            val prompt = etPrompt.text?.toString()?.trim() ?: ""
            if (prompt.isEmpty()) {
                Toast.makeText(this, "수정 내용을 입력해주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val code = binding.codeEditor.text.toString()
            val ext = File(filePath).extension.lowercase()
            val fileType = when (ext) {
                "smali" -> "Smali (Android Dalvik bytecode assembly)"
                "java" -> "Java"
                "kt" -> "Kotlin"
                "xml" -> "XML (Android layout/manifest)"
                "json" -> "JSON"
                else -> ext.ifEmpty { "text" }
            }

            btnSend.isEnabled = false
            etPrompt.isEnabled = false
            progressBar.visibility = View.VISIBLE
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = "Claude AI가 코드를 분석하고 수정 중..."

            lifecycleScope.launch {
                val result = claudeClient.modifyCode(code, prompt, fileType)

                result.onSuccess { modifiedCode ->
                    ignoreTextChange = true
                    val oldText = binding.codeEditor.text.toString()
                    undoStack.push(UndoItem(0, oldText, modifiedCode))
                    redoStack.clear()
                    binding.codeEditor.setText(modifiedCode)
                    ignoreTextChange = false

                    applyHighlighting()
                    updateLineNumbers()
                    isModified = true
                    updateTitle()

                    Toast.makeText(this@CodeEditorActivity, "AI 수정 완료", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }

                result.onFailure { error ->
                    progressBar.visibility = View.GONE
                    tvStatus.text = "오류: ${error.message}"
                    tvStatus.setTextColor(getColor(R.color.error))
                    btnSend.isEnabled = true
                    etPrompt.isEnabled = true
                }
            }
        }

        dialog.show()
    }

    private fun applyHighlighting() {
        val text = binding.codeEditor.text.toString()
        val ext = File(filePath).extension.lowercase()

        val highlighted = when (ext) {
            "smali" -> SyntaxHighlighter.highlightSmali(text)
            "java" -> SyntaxHighlighter.highlightJava(text)
            "xml" -> SyntaxHighlighter.highlightXml(text)
            else -> null
        }

        if (highlighted != null) {
            ignoreTextChange = true
            val cursorPos = binding.codeEditor.selectionStart
            binding.codeEditor.setText(highlighted)
            binding.codeEditor.setSelection(minOf(cursorPos, highlighted.length))
            ignoreTextChange = false
        }
    }

    private fun updateLineNumbers() {
        val text = binding.codeEditor.text.toString()
        val lineCount = text.count { it == '\n' } + 1
        val numbers = (1..lineCount).joinToString("\n")
        binding.lineNumbers.text = numbers
    }

    private fun undo() {
        if (undoStack.isEmpty()) return
        val item = undoStack.pop()
        redoStack.push(item)
        ignoreTextChange = true
        val editable = binding.codeEditor.text
        editable.replace(item.start, item.start + item.after.length, item.before)
        binding.codeEditor.setSelection(item.start + item.before.length)
        ignoreTextChange = false
        isModified = true
        updateTitle()
        updateLineNumbers()
    }

    private fun redo() {
        if (redoStack.isEmpty()) return
        val item = redoStack.pop()
        undoStack.push(item)
        ignoreTextChange = true
        val editable = binding.codeEditor.text
        editable.replace(item.start, item.start + item.before.length, item.after)
        binding.codeEditor.setSelection(item.start + item.after.length)
        ignoreTextChange = false
        isModified = true
        updateTitle()
        updateLineNumbers()
    }

    private fun saveFile() {
        try {
            File(filePath).writeText(binding.codeEditor.text.toString())
            isModified = false
            updateTitle()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showSearchDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Search")

        val input = EditText(this)
        input.hint = "Search text..."
        builder.setView(input)

        builder.setPositiveButton("Find") { _, _ ->
            val query = input.text.toString()
            if (query.isNotEmpty()) {
                val text = binding.codeEditor.text.toString()
                val index = text.indexOf(query, binding.codeEditor.selectionEnd)
                if (index >= 0) {
                    binding.codeEditor.setSelection(index, index + query.length)
                    binding.codeEditor.requestFocus()
                } else {
                    val fromStart = text.indexOf(query)
                    if (fromStart >= 0) {
                        binding.codeEditor.setSelection(fromStart, fromStart + query.length)
                    } else {
                        Toast.makeText(this, "Not found", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun updateTitle() {
        val name = File(filePath).name
        supportActionBar?.title = if (isModified) "$name *" else name
    }

    private fun handleBack() {
        if (isModified) {
            AlertDialog.Builder(this)
                .setTitle("Unsaved Changes")
                .setMessage("Save before closing?")
                .setPositiveButton("Save") { _, _ -> saveFile(); finish() }
                .setNegativeButton("Discard") { _, _ -> finish() }
                .setNeutralButton("Cancel", null)
                .show()
        } else {
            finish()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Save").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 2, 0, "Highlight").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, 3, 0, "API Key 설정").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            1 -> { saveFile(); true }
            2 -> { applyHighlighting(); true }
            3 -> { showApiKeyDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
