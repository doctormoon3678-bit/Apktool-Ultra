package com.apktool.ultra.core

class ArmDisassembler {

    data class Instruction(
        val address: Long,
        val bytes: ByteArray,
        val mnemonic: String,
        val operands: String,
        val comment: String = ""
    ) {
        val hexBytes: String get() = bytes.joinToString(" ") { "%02X".format(it) }
        val fullText: String get() = if (operands.isEmpty()) mnemonic else "$mnemonic $operands"
    }

    fun disassembleArm(data: ByteArray, baseAddr: Long, symbols: Map<Long, String> = emptyMap()): List<Instruction> {
        val instructions = mutableListOf<Instruction>()
        var offset = 0

        while (offset + 4 <= data.size) {
            val addr = baseAddr + offset
            val bytes = data.copyOfRange(offset, offset + 4)
            val word = readInt32LE(data, offset)
            val cond = (word ushr 28) and 0xF
            val condStr = conditionString(cond)

            val insn = decodeArm32(word, addr, condStr, symbols)
            val comment = symbols[addr]?.let { " ; <$it>" } ?: ""

            instructions.add(Instruction(addr, bytes, insn.first, insn.second, comment))
            offset += 4
        }
        return instructions
    }

    fun disassembleThumb(data: ByteArray, baseAddr: Long, symbols: Map<Long, String> = emptyMap()): List<Instruction> {
        val instructions = mutableListOf<Instruction>()
        var offset = 0

        while (offset + 2 <= data.size) {
            val addr = baseAddr + offset
            val hw1 = readInt16LE(data, offset)

            if (isThumb32(hw1) && offset + 4 <= data.size) {
                val hw2 = readInt16LE(data, offset + 2)
                val bytes = data.copyOfRange(offset, offset + 4)
                val insn = decodeThumb32(hw1, hw2, addr)
                val comment = symbols[addr]?.let { " ; <$it>" } ?: ""
                instructions.add(Instruction(addr, bytes, insn.first, insn.second, comment))
                offset += 4
            } else {
                val bytes = data.copyOfRange(offset, offset + 2)
                val insn = decodeThumb16(hw1, addr)
                val comment = symbols[addr]?.let { " ; <$it>" } ?: ""
                instructions.add(Instruction(addr, bytes, insn.first, insn.second, comment))
                offset += 2
            }
        }
        return instructions
    }

    fun disassembleArm64(data: ByteArray, baseAddr: Long, symbols: Map<Long, String> = emptyMap()): List<Instruction> {
        val instructions = mutableListOf<Instruction>()
        var offset = 0

        while (offset + 4 <= data.size) {
            val addr = baseAddr + offset
            val bytes = data.copyOfRange(offset, offset + 4)
            val word = readInt32LE(data, offset)
            val insn = decodeArm64(word, addr, symbols)
            val comment = symbols[addr]?.let { " ; <$it>" } ?: ""
            instructions.add(Instruction(addr, bytes, insn.first, insn.second, comment))
            offset += 4
        }
        return instructions
    }

    private fun decodeArm32(word: Int, addr: Long, cond: String, symbols: Map<Long, String>): Pair<String, String> {
        val op1 = (word ushr 25) and 0x7

        return when {
            // Branch
            (word ushr 24) and 0xF == 0xA -> {
                val offset = signExtend((word and 0x00FFFFFF) shl 2, 26)
                val target = addr + 8 + offset
                val sym = symbols[target]?.let { " <$it>" } ?: ""
                "b$cond" to "0x${target.toString(16)}$sym"
            }
            (word ushr 24) and 0xF == 0xB -> {
                val offset = signExtend((word and 0x00FFFFFF) shl 2, 26)
                val target = addr + 8 + offset
                val sym = symbols[target]?.let { " <$it>" } ?: ""
                "bl$cond" to "0x${target.toString(16)}$sym"
            }
            // BX
            word and 0x0FFFFFF0 == 0x012FFF10 -> {
                val rm = word and 0xF
                "bx$cond" to regName(rm)
            }
            // BLX register
            word and 0x0FFFFFF0 == 0x012FFF30 -> {
                val rm = word and 0xF
                "blx$cond" to regName(rm)
            }
            // Data processing
            op1 == 0 || op1 == 1 -> {
                val opcode = (word ushr 21) and 0xF
                val s = if ((word ushr 20) and 1 == 1) "s" else ""
                val rn = (word ushr 16) and 0xF
                val rd = (word ushr 12) and 0xF
                val op2str = if (op1 == 0) shiftedRegStr(word) else "#${rotatedImm(word)}"

                when (opcode) {
                    0x0 -> "and$s$cond" to "${regName(rd)}, ${regName(rn)}, $op2str"
                    0x1 -> "eor$s$cond" to "${regName(rd)}, ${regName(rn)}, $op2str"
                    0x2 -> "sub$s$cond" to "${regName(rd)}, ${regName(rn)}, $op2str"
                    0x3 -> "rsb$s$cond" to "${regName(rd)}, ${regName(rn)}, $op2str"
                    0x4 -> "add$s$cond" to "${regName(rd)}, ${regName(rn)}, $op2str"
                    0x5 -> "adc$s$cond" to "${regName(rd)}, ${regName(rn)}, $op2str"
                    0x8 -> "tst$cond" to "${regName(rn)}, $op2str"
                    0x9 -> "teq$cond" to "${regName(rn)}, $op2str"
                    0xA -> "cmp$cond" to "${regName(rn)}, $op2str"
                    0xB -> "cmn$cond" to "${regName(rn)}, $op2str"
                    0xC -> "orr$s$cond" to "${regName(rd)}, ${regName(rn)}, $op2str"
                    0xD -> "mov$s$cond" to "${regName(rd)}, $op2str"
                    0xE -> "bic$s$cond" to "${regName(rd)}, ${regName(rn)}, $op2str"
                    0xF -> "mvn$s$cond" to "${regName(rd)}, $op2str"
                    else -> "dp_$opcode$cond" to "${regName(rd)}, ${regName(rn)}, $op2str"
                }
            }
            // LDR/STR
            op1 == 2 || op1 == 3 -> {
                val isLoad = (word ushr 20) and 1 == 1
                val isByte = (word ushr 22) and 1 == 1
                val rn = (word ushr 16) and 0xF
                val rd = (word ushr 12) and 0xF
                val offset12 = word and 0xFFF
                val mnem = (if (isLoad) "ldr" else "str") + (if (isByte) "b" else "") + cond
                mnem to "${regName(rd)}, [${regName(rn)}, #$offset12]"
            }
            // LDM/STM
            op1 == 4 -> {
                val isLoad = (word ushr 20) and 1 == 1
                val rn = (word ushr 16) and 0xF
                val regList = (0..15).filter { word and (1 shl it) != 0 }.joinToString(", ") { regName(it) }
                val mnem = if (isLoad) "ldm$cond" else "stm$cond"
                mnem to "${regName(rn)}, {$regList}"
            }
            // SVC
            (word ushr 24) and 0xF == 0xF -> {
                "svc$cond" to "#${word and 0x00FFFFFF}"
            }
            // NOP
            word == 0xE1A00000.toInt() -> "nop" to ""
            else -> ".word" to "0x${word.toLong().and(0xFFFFFFFFL).toString(16)}"
        }
    }

    private fun decodeThumb16(hw: Int, addr: Long): Pair<String, String> {
        return when {
            hw and 0xF800 == 0x4800 -> {
                val rd = (hw ushr 8) and 7
                val offset = (hw and 0xFF) * 4
                "ldr" to "r$rd, [pc, #$offset]"
            }
            hw and 0xFFC0 == 0x4600 -> {
                val rd = (hw and 7) or ((hw ushr 4) and 8)
                val rm = (hw ushr 3) and 0xF
                "mov" to "${regName(rd)}, ${regName(rm)}"
            }
            hw and 0xFF00 == 0xBE00 -> "bkpt" to "#${hw and 0xFF}"
            hw and 0xFF00 == 0xDF00 -> "svc" to "#${hw and 0xFF}"
            hw and 0xFE00 == 0xB400 -> {
                val regs = (0..7).filter { hw and (1 shl it) != 0 }.map { "r$it" }
                val lr = if (hw and 0x100 != 0) listOf("lr") else emptyList()
                "push" to "{${(regs + lr).joinToString(", ")}}"
            }
            hw and 0xFE00 == 0xBC00 -> {
                val regs = (0..7).filter { hw and (1 shl it) != 0 }.map { "r$it" }
                val pc = if (hw and 0x100 != 0) listOf("pc") else emptyList()
                "pop" to "{${(regs + pc).joinToString(", ")}}"
            }
            hw and 0xF800 == 0x2000 -> {
                val rd = (hw ushr 8) and 7
                "movs" to "r$rd, #${hw and 0xFF}"
            }
            hw and 0xF800 == 0x2800 -> {
                val rn = (hw ushr 8) and 7
                "cmp" to "r$rn, #${hw and 0xFF}"
            }
            hw and 0xF800 == 0x3000 -> {
                val rd = (hw ushr 8) and 7
                "adds" to "r$rd, #${hw and 0xFF}"
            }
            hw and 0xF800 == 0x3800 -> {
                val rd = (hw ushr 8) and 7
                "subs" to "r$rd, #${hw and 0xFF}"
            }
            hw and 0xFF87 == 0x4700 -> {
                val rm = (hw ushr 3) and 0xF
                "bx" to regName(rm)
            }
            hw and 0xFF87 == 0x4780 -> {
                val rm = (hw ushr 3) and 0xF
                "blx" to regName(rm)
            }
            hw and 0xF000 == 0xD000 -> {
                val cond = (hw ushr 8) and 0xF
                val offset = signExtend((hw and 0xFF) shl 1, 9)
                val target = addr + 4 + offset
                "b${conditionString(cond)}" to "0x${target.toString(16)}"
            }
            hw and 0xF800 == 0xE000 -> {
                val offset = signExtend((hw and 0x7FF) shl 1, 12)
                val target = addr + 4 + offset
                "b" to "0x${target.toString(16)}"
            }
            hw == 0xBF00 -> "nop" to ""
            else -> ".short" to "0x${hw.toString(16)}"
        }
    }

    private fun decodeThumb32(hw1: Int, hw2: Int, addr: Long): Pair<String, String> {
        val op1 = (hw1 ushr 11) and 3
        return when {
            op1 == 2 && hw1 and 0xF800 == 0xF000 && hw2 and 0xD000 == 0x9000 -> {
                val s = (hw1 ushr 10) and 1
                val imm10 = hw1 and 0x3FF
                val j1 = (hw2 ushr 13) and 1
                val j2 = (hw2 ushr 11) and 1
                val imm11 = hw2 and 0x7FF
                val i1 = (j1 xor s xor 1) and 1
                val i2 = (j2 xor s xor 1) and 1
                val offset = signExtend(
                    (s shl 24) or (i1 shl 23) or (i2 shl 22) or (imm10 shl 12) or (imm11 shl 1), 25
                )
                val target = addr + 4 + offset
                "bl" to "0x${target.toString(16)}"
            }
            else -> ".word" to "0x${((hw1 shl 16) or hw2).toLong().and(0xFFFFFFFFL).toString(16)}"
        }
    }

    private fun decodeArm64(word: Int, addr: Long, symbols: Map<Long, String>): Pair<String, String> {
        val op0 = (word ushr 25) and 0xF

        return when {
            // NOP
            word == 0xD503201F.toInt() -> "nop" to ""
            // RET
            word and 0xFFFFFC1F.toInt() == 0xD65F0000.toInt() -> {
                val rn = (word ushr 5) and 0x1F
                "ret" to if (rn == 30) "" else "x$rn"
            }
            // B
            word and 0xFC000000.toInt() == 0x14000000 -> {
                val imm26 = signExtend((word and 0x03FFFFFF) shl 2, 28)
                val target = addr + imm26
                val sym = symbols[target]?.let { " <$it>" } ?: ""
                "b" to "0x${target.toString(16)}$sym"
            }
            // BL
            word and 0xFC000000.toInt() == 0x94000000.toInt() -> {
                val imm26 = signExtend((word and 0x03FFFFFF) shl 2, 28)
                val target = addr + imm26
                val sym = symbols[target]?.let { " <$it>" } ?: ""
                "bl" to "0x${target.toString(16)}$sym"
            }
            // BR
            word and 0xFFFFFC1F.toInt() == 0xD61F0000.toInt() -> {
                val rn = (word ushr 5) and 0x1F
                "br" to "x$rn"
            }
            // BLR
            word and 0xFFFFFC1F.toInt() == 0xD63F0000.toInt() -> {
                val rn = (word ushr 5) and 0x1F
                "blr" to "x$rn"
            }
            // B.cond
            word and 0xFF000010.toInt() == 0x54000000 -> {
                val cond = word and 0xF
                val imm19 = signExtend(((word ushr 5) and 0x7FFFF) shl 2, 21)
                val target = addr + imm19
                "b.${conditionStringA64(cond)}" to "0x${target.toString(16)}"
            }
            // CBZ/CBNZ
            word and 0x7E000000 == 0x34000000 -> {
                val sf = (word ushr 31) and 1
                val op = (word ushr 24) and 1
                val imm19 = signExtend(((word ushr 5) and 0x7FFFF) shl 2, 21)
                val rt = word and 0x1F
                val target = addr + imm19
                val prefix = if (sf == 1) "x" else "w"
                val mnem = if (op == 0) "cbz" else "cbnz"
                mnem to "$prefix$rt, 0x${target.toString(16)}"
            }
            // MOV immediate (MOVZ)
            word and 0x1F800000 == 0x12800000 || word and 0x1F800000 == 0x12A00000 -> {
                val sf = (word ushr 31) and 1
                val hw = (word ushr 21) and 3
                val imm16 = (word ushr 5) and 0xFFFF
                val rd = word and 0x1F
                val prefix = if (sf == 1) "x" else "w"
                val shifted = imm16.toLong() shl (hw * 16)
                "movz" to "$prefix$rd, #0x${shifted.toString(16)}"
            }
            // ADD/SUB immediate
            word and 0x1F000000 == 0x11000000 -> {
                val sf = (word ushr 31) and 1
                val op = (word ushr 30) and 1
                val sh = (word ushr 22) and 1
                val imm12 = (word ushr 10) and 0xFFF
                val rn = (word ushr 5) and 0x1F
                val rd = word and 0x1F
                val prefix = if (sf == 1) "x" else "w"
                val mnem = if (op == 0) "add" else "sub"
                val immVal = if (sh == 1) imm12 shl 12 else imm12
                mnem to "$prefix$rd, $prefix$rn, #$immVal"
            }
            // LDR/STR unsigned offset
            word and 0x3B000000 == 0x39000000 -> {
                val size = (word ushr 30) and 3
                val isLoad = (word ushr 22) and 1 == 1
                val imm12 = (word ushr 10) and 0xFFF
                val rn = (word ushr 5) and 0x1F
                val rt = word and 0x1F
                val scale = size
                val offset = imm12 shl scale
                val mnem = if (isLoad) "ldr" else "str"
                val suffix = when (size) { 0 -> "b"; 1 -> "h"; 2 -> ""; 3 -> ""; else -> "" }
                val prefix = if (size == 3) "x" else "w"
                "$mnem$suffix" to "$prefix$rt, [x$rn, #$offset]"
            }
            // STP/LDP
            word and 0x7E000000 == 0x28000000 -> {
                val opc = (word ushr 30) and 3
                val isLoad = (word ushr 22) and 1 == 1
                val imm7 = signExtend(((word ushr 15) and 0x7F) shl (2 + opc), 7 + 2 + opc)
                val rt2 = (word ushr 10) and 0x1F
                val rn = (word ushr 5) and 0x1F
                val rt = word and 0x1F
                val prefix = if (opc >= 2) "x" else "w"
                val mnem = if (isLoad) "ldp" else "stp"
                mnem to "$prefix$rt, $prefix$rt2, [x$rn, #$imm7]"
            }
            // SVC
            word and 0xFFE0001F.toInt() == 0xD4000001.toInt() -> {
                val imm16 = (word ushr 5) and 0xFFFF
                "svc" to "#0x${imm16.toString(16)}"
            }
            else -> ".inst" to "0x${word.toLong().and(0xFFFFFFFFL).toString(16)}"
        }
    }

    private fun isThumb32(hw: Int): Boolean = (hw and 0xE000) == 0xE000 && (hw and 0x1800) != 0

    private fun regName(r: Int): String = when (r) {
        13 -> "sp"; 14 -> "lr"; 15 -> "pc"; else -> "r$r"
    }

    private fun conditionString(cond: Int): String = when (cond) {
        0 -> "eq"; 1 -> "ne"; 2 -> "cs"; 3 -> "cc"
        4 -> "mi"; 5 -> "pl"; 6 -> "vs"; 7 -> "vc"
        8 -> "hi"; 9 -> "ls"; 10 -> "ge"; 11 -> "lt"
        12 -> "gt"; 13 -> "le"; 14 -> ""; 15 -> ""
        else -> ""
    }

    private fun conditionStringA64(cond: Int): String = when (cond) {
        0 -> "eq"; 1 -> "ne"; 2 -> "cs"; 3 -> "cc"
        4 -> "mi"; 5 -> "pl"; 6 -> "vs"; 7 -> "vc"
        8 -> "hi"; 9 -> "ls"; 10 -> "ge"; 11 -> "lt"
        12 -> "gt"; 13 -> "le"; 14 -> "al"; else -> "nv"
    }

    private fun shiftedRegStr(word: Int): String {
        val rm = word and 0xF
        val shiftType = (word ushr 5) and 3
        val shiftAmt = (word ushr 7) and 0x1F
        val shiftName = when (shiftType) { 0 -> "lsl"; 1 -> "lsr"; 2 -> "asr"; 3 -> "ror"; else -> "?" }
        return if (shiftAmt == 0) regName(rm) else "${regName(rm)}, $shiftName #$shiftAmt"
    }

    private fun rotatedImm(word: Int): Int {
        val imm = word and 0xFF
        val rot = ((word ushr 8) and 0xF) * 2
        return Integer.rotateRight(imm, rot)
    }

    private fun signExtend(value: Int, bits: Int): Long {
        val mask = 1 shl (bits - 1)
        return ((value xor mask) - mask).toLong()
    }

    companion object {
        fun assembleArm32(mnemonic: String, operands: String): ByteArray? {
            val parts = mnemonic.lowercase().trim()
            return when {
                parts == "nop" -> intToBytes(0xE1A00000.toInt())
                parts.startsWith("mov") -> {
                    val regs = operands.split(",").map { it.trim() }
                    if (regs.size == 2 && regs[1].startsWith("#")) {
                        val rd = parseReg(regs[0])
                        val imm = regs[1].removePrefix("#").trim().toIntOrNull() ?: return null
                        if (rd < 0 || imm < 0 || imm > 255) return null
                        intToBytes(0xE3A00000 or (rd shl 12) or imm)
                    } else null
                }
                parts == "bx" -> {
                    val rm = parseReg(operands.trim())
                    if (rm < 0) null else intToBytes(0xE12FFF10 or rm)
                }
                else -> null
            }
        }

        private fun parseReg(name: String): Int = when (name.lowercase().trim()) {
            "sp" -> 13; "lr" -> 14; "pc" -> 15
            else -> name.removePrefix("r").removePrefix("R").toIntOrNull() ?: -1
        }

        private fun intToBytes(value: Int): ByteArray = byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }
}
