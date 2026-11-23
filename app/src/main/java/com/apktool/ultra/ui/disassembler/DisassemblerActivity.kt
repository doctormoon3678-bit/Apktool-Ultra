package com.apktool.ultra.ui.disassembler

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.apktool.ultra.core.ArmDisassembler
import com.apktool.ultra.core.ElfParser
import com.apktool.ultra.databinding.ActivityDisassemblerBinding
import com.apktool.ultra.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DisassemblerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDisassemblerBinding
    private val elfParser = ElfParser()
    private val disassembler = ArmDisassembler()
    private var elfInfo: ElfParser.ElfInfo? = null
    private var soFile: File? = null
    private var currentView = ViewMode.SECTIONS

    enum class ViewMode { SECTIONS, SYMBOLS, DISASM, HEX, STRINGS, HEADERS }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDisassemblerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val soPath = intent.getStringExtra("so_path") ?: run { finish(); return }
        soFile = File(soPath)
        supportActionBar?.title = "Ghidra: ${soFile!!.name}"

        binding.contentList.layoutManager = LinearLayoutManager(this)
        binding.disasmText.typeface = Typeface.MONOSPACE
        binding.disasmText.textSize = 12f

        setupTabs()
        parseElf()
    }

    private fun setupTabs() {
        binding.tabSections.setOnClickListener { switchView(ViewMode.SECTIONS) }
        binding.tabSymbols.setOnClickListener { switchView(ViewMode.SYMBOLS) }
        binding.tabDisasm.setOnClickListener { switchView(ViewMode.DISASM) }
        binding.tabHex.setOnClickListener { switchView(ViewMode.HEX) }
        binding.tabStrings.setOnClickListener { switchView(ViewMode.STRINGS) }
        binding.tabHeaders.setOnClickListener { switchView(ViewMode.HEADERS) }
    }

    private fun parseElf() {
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                elfInfo = withContext(Dispatchers.IO) {
                    elfParser.parse(soFile!!)
                }
                binding.progressBar.visibility = View.GONE

                val info = elfInfo!!
                binding.elfInfo.text = buildString {
                    appendLine("Architecture: ${info.header.machineString}")
                    appendLine("Type: ${info.header.typeString}")
                    appendLine("Bits: ${if (info.header.is64Bit) "64-bit" else "32-bit"}")
                    appendLine("Endian: ${if (info.header.isLittleEndian) "Little" else "Big"}")
                    appendLine("Entry: 0x${info.header.entry.toString(16)}")
                    appendLine("Sections: ${info.sections.size}")
                    appendLine("Symbols: ${info.symbols.size + info.dynamicSymbols.size}")
                }

                switchView(ViewMode.SECTIONS)
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.elfInfo.text = "Parse error: ${e.message}"
            }
        }
    }

    private fun switchView(mode: ViewMode) {
        currentView = mode
        val info = elfInfo ?: return

        binding.contentList.visibility = View.GONE
        binding.disasmText.visibility = View.GONE

        when (mode) {
            ViewMode.SECTIONS -> showSections(info)
            ViewMode.SYMBOLS -> showSymbols(info)
            ViewMode.DISASM -> showDisassembly(info)
            ViewMode.HEX -> showHex(info)
            ViewMode.STRINGS -> showStrings(info)
            ViewMode.HEADERS -> showHeaders(info)
        }
    }

    private fun showSections(info: ElfParser.ElfInfo) {
        binding.contentList.visibility = View.VISIBLE

        val items = info.sections.mapIndexed { idx, sec ->
            DisasmListItem(
                title = sec.name.ifEmpty { "[${idx}]" },
                subtitle = "${sec.typeString} | Addr: 0x${sec.addr.toString(16)} | Size: ${sec.size}",
                detail = buildString {
                    if (sec.isExecutable) append("X ")
                    if (sec.isWritable) append("W ")
                    if (sec.isAlloc) append("A ")
                },
                onClick = { onSectionClick(sec) }
            )
        }

        binding.contentList.adapter = DisasmListAdapter(items)
    }

    private fun showSymbols(info: ElfParser.ElfInfo) {
        binding.contentList.visibility = View.VISIBLE

        val allSymbols = (info.symbols + info.dynamicSymbols)
            .filter { it.name.isNotEmpty() }
            .sortedWith(compareBy({ !it.isFunction }, { it.name }))

        val items = allSymbols.map { sym ->
            DisasmListItem(
                title = sym.name,
                subtitle = "${sym.typeString} ${sym.bindString} | 0x${sym.value.toString(16)} | Size: ${sym.size}",
                detail = if (sym.isFunction) "FUNC" else sym.typeString,
                onClick = { if (sym.isFunction && sym.value > 0) disassembleFunction(sym) }
            )
        }

        binding.contentList.adapter = DisasmListAdapter(items)
    }

    private fun showDisassembly(info: ElfParser.ElfInfo) {
        binding.disasmText.visibility = View.VISIBLE

        lifecycleScope.launch {
            val textSection = info.sections.find { it.name == ".text" }
            if (textSection == null) {
                binding.disasmText.text = "No .text section found"
                return@launch
            }

            binding.progressBar.visibility = View.VISIBLE

            val text = withContext(Dispatchers.IO) {
                val data = elfParser.readSectionData(soFile!!, textSection)
                val symbolMap = buildSymbolMap(info)

                val instructions = when {
                    info.header.isArm64 -> disassembler.disassembleArm64(data, textSection.addr, symbolMap)
                    info.header.isArm -> disassembler.disassembleArm(data, textSection.addr, symbolMap)
                    else -> disassembler.disassembleArm(data, textSection.addr, symbolMap)
                }

                val sb = StringBuilder()
                sb.appendLine("; Section: .text")
                sb.appendLine("; Address: 0x${textSection.addr.toString(16)}")
                sb.appendLine("; Size: ${textSection.size} bytes")
                sb.appendLine()

                for (insn in instructions.take(5000)) {
                    val sym = symbolMap[insn.address]
                    if (sym != null) {
                        sb.appendLine()
                        sb.appendLine("; ---- <$sym> ----")
                    }
                    sb.appendLine("  %08X:  %-12s  %s%s".format(
                        insn.address,
                        insn.hexBytes,
                        insn.fullText,
                        insn.comment
                    ))
                }
                sb.toString()
            }

            binding.progressBar.visibility = View.GONE
            binding.disasmText.text = text
        }
    }

    private fun showHex(info: ElfParser.ElfInfo) {
        binding.disasmText.visibility = View.VISIBLE

        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                val bytes = soFile!!.readBytes()
                FileUtils.formatHexDump(bytes, length = minOf(bytes.size, 8192))
            }
            binding.disasmText.text = text
        }
    }

    private fun showStrings(info: ElfParser.ElfInfo) {
        binding.disasmText.visibility = View.VISIBLE

        lifecycleScope.launch {
            val strings = withContext(Dispatchers.IO) {
                val data = soFile!!.readBytes()
                extractStrings(data)
            }

            val text = strings.joinToString("\n") { (offset, str) ->
                "%08X: %s".format(offset, str)
            }
            binding.disasmText.text = if (text.isEmpty()) "No strings found" else text
        }
    }

    private fun showHeaders(info: ElfParser.ElfInfo) {
        binding.disasmText.visibility = View.VISIBLE

        val sb = StringBuilder()
        sb.appendLine("=== Program Headers ===")
        for ((idx, ph) in info.programHeaders.withIndex()) {
            sb.appendLine()
            sb.appendLine("[$idx] ${ph.typeString}")
            sb.appendLine("    Offset: 0x${ph.offset.toString(16)}")
            sb.appendLine("    VAddr:  0x${ph.vaddr.toString(16)}")
            sb.appendLine("    FileSize: ${ph.fileSize}")
            sb.appendLine("    MemSize:  ${ph.memSize}")
            sb.appendLine("    Flags: 0x${ph.flags.toString(16)}")
        }
        binding.disasmText.text = sb.toString()
    }

    private fun onSectionClick(section: ElfParser.SectionHeader) {
        if (section.size == 0L) return

        val options = mutableListOf("View Hex Dump")
        if (section.isExecutable) options.add("Disassemble")
        if (section.type == 3) options.add("View Strings")
        options.add("Patch Bytes")

        AlertDialog.Builder(this)
            .setTitle(section.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "View Hex Dump" -> viewSectionHex(section)
                    "Disassemble" -> disassembleSection(section)
                    "View Strings" -> viewSectionStrings(section)
                    "Patch Bytes" -> showPatchDialog(section)
                }
            }
            .show()
    }

    private fun viewSectionHex(section: ElfParser.SectionHeader) {
        lifecycleScope.launch {
            binding.disasmText.visibility = View.VISIBLE
            binding.contentList.visibility = View.GONE

            val text = withContext(Dispatchers.IO) {
                val data = elfParser.readSectionData(soFile!!, section)
                FileUtils.formatHexDump(data, baseAddr = section.addr, length = minOf(data.size, 8192))
            }
            binding.disasmText.text = "; Section: ${section.name}\n\n$text"
        }
    }

    private fun disassembleSection(section: ElfParser.SectionHeader) {
        val info = elfInfo ?: return
        lifecycleScope.launch {
            binding.disasmText.visibility = View.VISIBLE
            binding.contentList.visibility = View.GONE
            binding.progressBar.visibility = View.VISIBLE

            val text = withContext(Dispatchers.IO) {
                val data = elfParser.readSectionData(soFile!!, section)
                val symbolMap = buildSymbolMap(info)

                val instructions = when {
                    info.header.isArm64 -> disassembler.disassembleArm64(data, section.addr, symbolMap)
                    info.header.isArm -> disassembler.disassembleArm(data, section.addr, symbolMap)
                    else -> disassembler.disassembleArm(data, section.addr, symbolMap)
                }

                val sb = StringBuilder()
                sb.appendLine("; Section: ${section.name}")
                sb.appendLine("; Size: ${section.size}")
                sb.appendLine()

                for (insn in instructions.take(5000)) {
                    val sym = symbolMap[insn.address]
                    if (sym != null) {
                        sb.appendLine()
                        sb.appendLine("; <$sym>:")
                    }
                    sb.appendLine("  %08X:  %-12s  %s%s".format(
                        insn.address, insn.hexBytes, insn.fullText, insn.comment
                    ))
                }
                sb.toString()
            }

            binding.progressBar.visibility = View.GONE
            binding.disasmText.text = text
        }
    }

    private fun viewSectionStrings(section: ElfParser.SectionHeader) {
        lifecycleScope.launch {
            binding.disasmText.visibility = View.VISIBLE
            binding.contentList.visibility = View.GONE

            val text = withContext(Dispatchers.IO) {
                val data = elfParser.readSectionData(soFile!!, section)
                extractStrings(data).joinToString("\n") { (off, str) ->
                    "%08X: %s".format(section.addr + off, str)
                }
            }
            binding.disasmText.text = text
        }
    }

    private fun showPatchDialog(section: ElfParser.SectionHeader) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Patch Bytes - ${section.name}")

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }

        val offsetInput = android.widget.EditText(this).apply {
            hint = "Offset (hex, e.g. 0x1000)"
        }
        val bytesInput = android.widget.EditText(this).apply {
            hint = "Bytes (hex, e.g. 00 BF 00 BF)"
        }

        layout.addView(offsetInput)
        layout.addView(bytesInput)
        builder.setView(layout)

        builder.setPositiveButton("Patch") { _, _ ->
            try {
                val offsetStr = offsetInput.text.toString().trim().removePrefix("0x")
                val offset = offsetStr.toLong(16)
                val bytesStr = bytesInput.text.toString().trim()
                val bytes = bytesStr.split(" ", ",").map { it.trim().toInt(16).toByte() }.toByteArray()

                elfParser.patchBytes(soFile!!, offset, bytes)
                Toast.makeText(this, "Patched ${bytes.size} bytes at 0x${offset.toString(16)}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Patch failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun disassembleFunction(sym: ElfParser.Symbol) {
        val info = elfInfo ?: return
        val textSection = info.sections.find { sec ->
            sym.value >= sec.addr && sym.value < sec.addr + sec.size && sec.isExecutable
        } ?: return

        lifecycleScope.launch {
            binding.disasmText.visibility = View.VISIBLE
            binding.contentList.visibility = View.GONE
            binding.progressBar.visibility = View.VISIBLE

            val text = withContext(Dispatchers.IO) {
                val sectionData = elfParser.readSectionData(soFile!!, textSection)
                val funcOffset = (sym.value - textSection.addr).toInt()
                val funcSize = if (sym.size > 0) sym.size.toInt() else 256
                val endOffset = minOf(funcOffset + funcSize, sectionData.size)

                if (funcOffset < 0 || funcOffset >= sectionData.size) return@withContext "; Invalid function offset"

                val funcData = sectionData.copyOfRange(funcOffset, endOffset)
                val symbolMap = buildSymbolMap(info)

                val instructions = when {
                    info.header.isArm64 -> disassembler.disassembleArm64(funcData, sym.value, symbolMap)
                    info.header.isArm -> disassembler.disassembleArm(funcData, sym.value, symbolMap)
                    else -> disassembler.disassembleArm(funcData, sym.value, symbolMap)
                }

                val sb = StringBuilder()
                sb.appendLine("; Function: ${sym.name}")
                sb.appendLine("; Address: 0x${sym.value.toString(16)}")
                sb.appendLine("; Size: ${sym.size}")
                sb.appendLine()

                for (insn in instructions) {
                    sb.appendLine("  %08X:  %-12s  %s%s".format(
                        insn.address, insn.hexBytes, insn.fullText, insn.comment
                    ))
                }
                sb.toString()
            }

            binding.progressBar.visibility = View.GONE
            binding.disasmText.text = text
        }
    }

    private fun buildSymbolMap(info: ElfParser.ElfInfo): Map<Long, String> {
        val map = mutableMapOf<Long, String>()
        for (sym in info.symbols + info.dynamicSymbols) {
            if (sym.name.isNotEmpty() && sym.value > 0) {
                map[sym.value] = sym.name
            }
        }
        return map
    }

    private fun extractStrings(data: ByteArray, minLength: Int = 4): List<Pair<Int, String>> {
        val result = mutableListOf<Pair<Int, String>>()
        var start = -1
        val sb = StringBuilder()

        for (i in data.indices) {
            val c = data[i].toInt() and 0xFF
            if (c in 32..126) {
                if (start == -1) start = i
                sb.append(c.toChar())
            } else {
                if (sb.length >= minLength) {
                    result.add(start to sb.toString())
                }
                sb.clear()
                start = -1
            }
        }
        if (sb.length >= minLength) {
            result.add(start to sb.toString())
        }

        return result.take(5000)
    }

    data class DisasmListItem(
        val title: String,
        val subtitle: String,
        val detail: String,
        val onClick: () -> Unit
    )
}
