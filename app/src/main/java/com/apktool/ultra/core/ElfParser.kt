package com.apktool.ultra.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ElfParser {

    data class ElfHeader(
        val is64Bit: Boolean,
        val isLittleEndian: Boolean,
        val type: Int,
        val machine: Int,
        val entry: Long,
        val phOffset: Long,
        val shOffset: Long,
        val phEntSize: Int,
        val phNum: Int,
        val shEntSize: Int,
        val shNum: Int,
        val shStrIndex: Int
    ) {
        val machineString: String get() = when (machine) {
            0x03 -> "x86 (i386)"
            0x28 -> "ARM"
            0x3E -> "x86-64"
            0xB7 -> "AArch64 (ARM64)"
            else -> "Unknown ($machine)"
        }

        val typeString: String get() = when (type) {
            0 -> "NONE"
            1 -> "REL (Relocatable)"
            2 -> "EXEC (Executable)"
            3 -> "DYN (Shared Object)"
            4 -> "CORE"
            else -> "Unknown ($type)"
        }

        val isArm: Boolean get() = machine == 0x28
        val isArm64: Boolean get() = machine == 0xB7
        val isX86: Boolean get() = machine == 0x03
        val isX86_64: Boolean get() = machine == 0x3E
    }

    data class SectionHeader(
        val name: String,
        val nameIndex: Int,
        val type: Int,
        val flags: Long,
        val addr: Long,
        val offset: Long,
        val size: Long,
        val link: Int,
        val info: Int,
        val addrAlign: Long,
        val entSize: Long
    ) {
        val typeString: String get() = when (type) {
            0 -> "NULL"
            1 -> "PROGBITS"
            2 -> "SYMTAB"
            3 -> "STRTAB"
            4 -> "RELA"
            5 -> "HASH"
            6 -> "DYNAMIC"
            7 -> "NOTE"
            8 -> "NOBITS"
            9 -> "REL"
            11 -> "DYNSYM"
            14 -> "INIT_ARRAY"
            15 -> "FINI_ARRAY"
            else -> "0x${type.toString(16)}"
        }

        val isExecutable: Boolean get() = flags and 0x4 != 0L
        val isWritable: Boolean get() = flags and 0x1 != 0L
        val isAlloc: Boolean get() = flags and 0x2 != 0L
    }

    data class Symbol(
        val name: String,
        val value: Long,
        val size: Long,
        val bind: Int,
        val type: Int,
        val sectionIndex: Int
    ) {
        val bindString: String get() = when (bind) {
            0 -> "LOCAL"
            1 -> "GLOBAL"
            2 -> "WEAK"
            else -> "UNKNOWN"
        }

        val typeString: String get() = when (type) {
            0 -> "NOTYPE"
            1 -> "OBJECT"
            2 -> "FUNC"
            3 -> "SECTION"
            4 -> "FILE"
            else -> "UNKNOWN"
        }

        val isFunction: Boolean get() = type == 2
    }

    data class ProgramHeader(
        val type: Int,
        val flags: Int,
        val offset: Long,
        val vaddr: Long,
        val paddr: Long,
        val fileSize: Long,
        val memSize: Long,
        val align: Long
    ) {
        val typeString: String get() = when (type) {
            0 -> "NULL"
            1 -> "LOAD"
            2 -> "DYNAMIC"
            3 -> "INTERP"
            4 -> "NOTE"
            6 -> "PHDR"
            7 -> "TLS"
            0x6474e550 -> "GNU_EH_FRAME"
            0x6474e551 -> "GNU_STACK"
            0x6474e552 -> "GNU_RELRO"
            else -> "0x${type.toString(16)}"
        }
    }

    data class ElfInfo(
        val header: ElfHeader,
        val sections: List<SectionHeader>,
        val symbols: List<Symbol>,
        val programHeaders: List<ProgramHeader>,
        val dynamicSymbols: List<Symbol>
    )

    fun parse(file: File): ElfInfo {
        val raf = RandomAccessFile(file, "r")
        val data = ByteArray(raf.length().toInt().coerceAtMost(50 * 1024 * 1024))
        raf.readFully(data)
        raf.close()

        val magic = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
        for (i in 0..3) {
            if (data[i] != magic[i]) throw IllegalArgumentException("Not an ELF file")
        }

        val is64Bit = data[4] == 2.toByte()
        val isLittleEndian = data[5] == 1.toByte()
        val order = if (isLittleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val buf = ByteBuffer.wrap(data).order(order)

        val header = parseHeader(buf, is64Bit, isLittleEndian)
        val sections = parseSections(buf, header)
        val programHeaders = parseProgramHeaders(buf, header)

        val shStrTab = if (header.shStrIndex in sections.indices) {
            val sec = sections[header.shStrIndex]
            data.copyOfRange(sec.offset.toInt(), (sec.offset + sec.size).toInt().coerceAtMost(data.size))
        } else ByteArray(0)

        val namedSections = sections.map { sec ->
            val name = readStringFromTable(shStrTab, sec.nameIndex)
            sec.copy(name = name)
        }

        val symbols = mutableListOf<Symbol>()
        val dynamicSymbols = mutableListOf<Symbol>()

        for (section in namedSections) {
            if (section.type == 2 || section.type == 11) { // SYMTAB or DYNSYM
                val strTabSection = namedSections.getOrNull(section.link) ?: continue
                val strTab = data.copyOfRange(
                    strTabSection.offset.toInt(),
                    (strTabSection.offset + strTabSection.size).toInt().coerceAtMost(data.size)
                )
                val entSize = if (section.entSize > 0) section.entSize.toInt()
                    else if (is64Bit) 24 else 16
                val count = (section.size / entSize).toInt()

                for (i in 0 until count) {
                    val off = section.offset.toInt() + i * entSize
                    if (off + entSize > data.size) break

                    val sym = if (is64Bit) {
                        val nameIdx = buf.getInt(off)
                        val info = data[off + 4].toInt() and 0xFF
                        val value = buf.getLong(off + 8)
                        val size = buf.getLong(off + 16)
                        Symbol(
                            name = readStringFromTable(strTab, nameIdx),
                            value = value,
                            size = size,
                            bind = info shr 4,
                            type = info and 0xF,
                            sectionIndex = buf.getShort(off + 6).toInt() and 0xFFFF
                        )
                    } else {
                        val nameIdx = buf.getInt(off)
                        val value = buf.getInt(off + 4).toLong() and 0xFFFFFFFFL
                        val size = buf.getInt(off + 8).toLong() and 0xFFFFFFFFL
                        val info = data[off + 12].toInt() and 0xFF
                        Symbol(
                            name = readStringFromTable(strTab, nameIdx),
                            value = value,
                            size = size,
                            bind = info shr 4,
                            type = info and 0xF,
                            sectionIndex = buf.getShort(off + 14).toInt() and 0xFFFF
                        )
                    }

                    if (section.type == 11) dynamicSymbols.add(sym)
                    else symbols.add(sym)
                }
            }
        }

        return ElfInfo(header, namedSections, symbols, programHeaders, dynamicSymbols)
    }

    fun readSectionData(file: File, section: SectionHeader): ByteArray {
        val raf = RandomAccessFile(file, "r")
        raf.seek(section.offset)
        val data = ByteArray(section.size.toInt().coerceAtMost(10 * 1024 * 1024))
        raf.readFully(data)
        raf.close()
        return data
    }

    fun patchBytes(file: File, offset: Long, bytes: ByteArray) {
        val raf = RandomAccessFile(file, "rw")
        raf.seek(offset)
        raf.write(bytes)
        raf.close()
    }

    private fun parseHeader(buf: ByteBuffer, is64Bit: Boolean, isLittleEndian: Boolean): ElfHeader {
        return if (is64Bit) {
            ElfHeader(
                is64Bit = true,
                isLittleEndian = isLittleEndian,
                type = buf.getShort(16).toInt() and 0xFFFF,
                machine = buf.getShort(18).toInt() and 0xFFFF,
                entry = buf.getLong(24),
                phOffset = buf.getLong(32),
                shOffset = buf.getLong(40),
                phEntSize = buf.getShort(54).toInt() and 0xFFFF,
                phNum = buf.getShort(56).toInt() and 0xFFFF,
                shEntSize = buf.getShort(58).toInt() and 0xFFFF,
                shNum = buf.getShort(60).toInt() and 0xFFFF,
                shStrIndex = buf.getShort(62).toInt() and 0xFFFF
            )
        } else {
            ElfHeader(
                is64Bit = false,
                isLittleEndian = isLittleEndian,
                type = buf.getShort(16).toInt() and 0xFFFF,
                machine = buf.getShort(18).toInt() and 0xFFFF,
                entry = buf.getInt(24).toLong() and 0xFFFFFFFFL,
                phOffset = buf.getInt(28).toLong() and 0xFFFFFFFFL,
                shOffset = buf.getInt(32).toLong() and 0xFFFFFFFFL,
                phEntSize = buf.getShort(42).toInt() and 0xFFFF,
                phNum = buf.getShort(44).toInt() and 0xFFFF,
                shEntSize = buf.getShort(46).toInt() and 0xFFFF,
                shNum = buf.getShort(48).toInt() and 0xFFFF,
                shStrIndex = buf.getShort(50).toInt() and 0xFFFF
            )
        }
    }

    private fun parseSections(buf: ByteBuffer, header: ElfHeader): List<SectionHeader> {
        val sections = mutableListOf<SectionHeader>()
        for (i in 0 until header.shNum) {
            val off = (header.shOffset + i * header.shEntSize).toInt()
            if (off + header.shEntSize > buf.capacity()) break

            val section = if (header.is64Bit) {
                SectionHeader(
                    name = "",
                    nameIndex = buf.getInt(off),
                    type = buf.getInt(off + 4),
                    flags = buf.getLong(off + 8),
                    addr = buf.getLong(off + 16),
                    offset = buf.getLong(off + 24),
                    size = buf.getLong(off + 32),
                    link = buf.getInt(off + 40),
                    info = buf.getInt(off + 44),
                    addrAlign = buf.getLong(off + 48),
                    entSize = buf.getLong(off + 56)
                )
            } else {
                SectionHeader(
                    name = "",
                    nameIndex = buf.getInt(off),
                    type = buf.getInt(off + 4),
                    flags = buf.getInt(off + 8).toLong() and 0xFFFFFFFFL,
                    addr = buf.getInt(off + 12).toLong() and 0xFFFFFFFFL,
                    offset = buf.getInt(off + 16).toLong() and 0xFFFFFFFFL,
                    size = buf.getInt(off + 20).toLong() and 0xFFFFFFFFL,
                    link = buf.getInt(off + 24),
                    info = buf.getInt(off + 28),
                    addrAlign = buf.getInt(off + 32).toLong() and 0xFFFFFFFFL,
                    entSize = buf.getInt(off + 36).toLong() and 0xFFFFFFFFL
                )
            }
            sections.add(section)
        }
        return sections
    }

    private fun parseProgramHeaders(buf: ByteBuffer, header: ElfHeader): List<ProgramHeader> {
        val phdrs = mutableListOf<ProgramHeader>()
        for (i in 0 until header.phNum) {
            val off = (header.phOffset + i * header.phEntSize).toInt()
            if (off + header.phEntSize > buf.capacity()) break

            val phdr = if (header.is64Bit) {
                ProgramHeader(
                    type = buf.getInt(off),
                    flags = buf.getInt(off + 4),
                    offset = buf.getLong(off + 8),
                    vaddr = buf.getLong(off + 16),
                    paddr = buf.getLong(off + 24),
                    fileSize = buf.getLong(off + 32),
                    memSize = buf.getLong(off + 40),
                    align = buf.getLong(off + 48)
                )
            } else {
                ProgramHeader(
                    type = buf.getInt(off),
                    offset = buf.getInt(off + 4).toLong() and 0xFFFFFFFFL,
                    vaddr = buf.getInt(off + 8).toLong() and 0xFFFFFFFFL,
                    paddr = buf.getInt(off + 12).toLong() and 0xFFFFFFFFL,
                    fileSize = buf.getInt(off + 16).toLong() and 0xFFFFFFFFL,
                    memSize = buf.getInt(off + 20).toLong() and 0xFFFFFFFFL,
                    flags = buf.getInt(off + 24),
                    align = buf.getInt(off + 28).toLong() and 0xFFFFFFFFL
                )
            }
            phdrs.add(phdr)
        }
        return phdrs
    }

    private fun readStringFromTable(table: ByteArray, offset: Int): String {
        if (offset < 0 || offset >= table.size) return ""
        val end = table.indexOf(0.toByte(), offset)
        val realEnd = if (end == -1) table.size else end
        return String(table, offset, realEnd - offset, Charsets.UTF_8)
    }

    private fun ByteArray.indexOf(byte: Byte, startIndex: Int): Int {
        for (i in startIndex until size) {
            if (this[i] == byte) return i
        }
        return -1
    }
}
