package com.apktool.ultra.utils

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import java.util.regex.Pattern

object SyntaxHighlighter {

    data class Theme(
        val keyword: Int = Color.parseColor("#CC7832"),
        val string: Int = Color.parseColor("#6A8759"),
        val comment: Int = Color.parseColor("#808080"),
        val number: Int = Color.parseColor("#6897BB"),
        val annotation: Int = Color.parseColor("#BBB529"),
        val type: Int = Color.parseColor("#FFC66D"),
        val directive: Int = Color.parseColor("#CC7832"),
        val register: Int = Color.parseColor("#9876AA"),
        val label: Int = Color.parseColor("#FFC66D"),
        val instruction: Int = Color.parseColor("#CC7832"),
        val address: Int = Color.parseColor("#6897BB"),
        val text: Int = Color.parseColor("#A9B7C6"),
        val background: Int = Color.parseColor("#2B2B2B"),
        val lineNumber: Int = Color.parseColor("#606366"),
        val selection: Int = Color.parseColor("#214283")
    )

    val darkTheme = Theme()

    val lightTheme = Theme(
        keyword = Color.parseColor("#0000FF"),
        string = Color.parseColor("#008000"),
        comment = Color.parseColor("#808080"),
        number = Color.parseColor("#0000FF"),
        annotation = Color.parseColor("#808000"),
        type = Color.parseColor("#000080"),
        directive = Color.parseColor("#0000FF"),
        register = Color.parseColor("#660099"),
        label = Color.parseColor("#000080"),
        instruction = Color.parseColor("#0000FF"),
        address = Color.parseColor("#0000FF"),
        text = Color.parseColor("#000000"),
        background = Color.parseColor("#FFFFFF"),
        lineNumber = Color.parseColor("#999999"),
        selection = Color.parseColor("#ADD8E6")
    )

    fun highlightSmali(code: String, theme: Theme = darkTheme): SpannableStringBuilder {
        val ssb = SpannableStringBuilder(code)

        val patterns = listOf(
            Pattern.compile("#[^\n]*") to theme.comment,
            Pattern.compile("\"[^\"]*\"") to theme.string,
            Pattern.compile("\\.(class|super|source|implements|field|method|end method|end field|annotation|end annotation|subannotation|end subannotation|enum|registers|locals|param|prologue|line|catch|catchall|packed-switch|end packed-switch|sparse-switch|end sparse-switch|array-data|end array-data)\\b") to theme.directive,
            Pattern.compile("\\b(public|private|protected|static|final|abstract|synthetic|interface|enum|bridge|varargs|native|strictfp|transient|volatile|synchronized|constructor|declared-synchronized)\\b") to theme.keyword,
            Pattern.compile("\\b[vp]\\d+\\b") to theme.register,
            Pattern.compile("\\b(invoke-virtual|invoke-super|invoke-direct|invoke-static|invoke-interface|invoke-virtual/range|invoke-super/range|invoke-direct/range|invoke-static/range|invoke-interface/range|move-result|move-result-wide|move-result-object|move-exception|return-void|return|return-wide|return-object|const|const/4|const/16|const/high16|const-wide|const-wide/16|const-wide/32|const-wide/high16|const-string|const-class|new-instance|new-array|filled-new-array|filled-new-array/range|check-cast|instance-of|array-length|throw|goto|goto/16|goto/32|if-eq|if-ne|if-lt|if-ge|if-gt|if-le|if-eqz|if-nez|if-ltz|if-gez|if-gtz|if-lez|iget|iget-wide|iget-object|iget-boolean|iget-byte|iget-char|iget-short|iput|iput-wide|iput-object|iput-boolean|iput-byte|iput-char|iput-short|sget|sget-wide|sget-object|sget-boolean|sget-byte|sget-char|sget-short|sput|sput-wide|sput-object|sput-boolean|sput-byte|sput-char|sput-short|aget|aget-wide|aget-object|aget-boolean|aget-byte|aget-char|aget-short|aput|aput-wide|aput-object|aput-boolean|aput-byte|aput-char|aput-short|add-int|sub-int|mul-int|div-int|rem-int|and-int|or-int|xor-int|shl-int|shr-int|ushr-int|move|move/from16|move/16|move-wide|move-wide/from16|move-wide/16|move-object|move-object/from16|move-object/16|nop|monitor-enter|monitor-exit)\\b") to theme.instruction,
            Pattern.compile(":\\w+") to theme.label,
            Pattern.compile("0x[0-9a-fA-F]+|\\b\\d+\\b") to theme.number,
            Pattern.compile("L[\\w/\\$]+;") to theme.type
        )

        for ((pattern, color) in patterns) {
            val matcher = pattern.matcher(code)
            while (matcher.find()) {
                ssb.setSpan(
                    ForegroundColorSpan(color),
                    matcher.start(),
                    matcher.end(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return ssb
    }

    fun highlightJava(code: String, theme: Theme = darkTheme): SpannableStringBuilder {
        val ssb = SpannableStringBuilder(code)

        val patterns = listOf(
            Pattern.compile("//[^\n]*|/\\*[\\s\\S]*?\\*/") to theme.comment,
            Pattern.compile("\"[^\"\\\\]*(\\\\.[^\"\\\\]*)*\"") to theme.string,
            Pattern.compile("\\b(abstract|assert|boolean|break|byte|case|catch|char|class|continue|default|do|double|else|enum|extends|final|finally|float|for|if|implements|import|instanceof|int|interface|long|native|new|package|private|protected|public|return|short|static|strictfp|super|switch|synchronized|this|throw|throws|transient|try|void|volatile|while|true|false|null)\\b") to theme.keyword,
            Pattern.compile("@\\w+") to theme.annotation,
            Pattern.compile("0x[0-9a-fA-F]+[lL]?|\\b\\d+(\\.\\d+)?[fFdDlL]?\\b") to theme.number,
            Pattern.compile("\\b[A-Z][a-zA-Z0-9_]*\\b") to theme.type
        )

        for ((pattern, color) in patterns) {
            val matcher = pattern.matcher(code)
            while (matcher.find()) {
                ssb.setSpan(
                    ForegroundColorSpan(color),
                    matcher.start(),
                    matcher.end(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return ssb
    }

    fun highlightXml(code: String, theme: Theme = darkTheme): SpannableStringBuilder {
        val ssb = SpannableStringBuilder(code)

        val patterns = listOf(
            Pattern.compile("<!--[\\s\\S]*?-->") to theme.comment,
            Pattern.compile("\"[^\"]*\"") to theme.string,
            Pattern.compile("</?\\w[\\w:-]*") to theme.keyword,
            Pattern.compile("\\b\\w+(?==)") to theme.type,
            Pattern.compile("/>|>|<") to theme.keyword
        )

        for ((pattern, color) in patterns) {
            val matcher = pattern.matcher(code)
            while (matcher.find()) {
                ssb.setSpan(
                    ForegroundColorSpan(color),
                    matcher.start(),
                    matcher.end(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return ssb
    }

    fun highlightAsm(code: String, theme: Theme = darkTheme): SpannableStringBuilder {
        val ssb = SpannableStringBuilder(code)

        val patterns = listOf(
            Pattern.compile(";[^\n]*") to theme.comment,
            Pattern.compile("\\b(mov|movs|movz|movn|add|adds|sub|subs|mul|div|and|orr|eor|bic|lsl|lsr|asr|ror|ldr|ldrb|ldrh|str|strb|strh|ldp|stp|push|pop|b|bl|blr|br|bx|blx|bne|beq|bgt|blt|bge|ble|bhi|bls|bcs|bcc|bmi|bpl|bvs|bvc|cmp|cmn|tst|teq|cbz|cbnz|ret|nop|svc|adr|adrp)\\b") to theme.instruction,
            Pattern.compile("\\b([xwrs]\\d+|sp|lr|pc|fp|ip|cpsr|spsr|wzr|xzr)\\b") to theme.register,
            Pattern.compile("#-?0x[0-9a-fA-F]+|#-?\\d+") to theme.number,
            Pattern.compile("0x[0-9a-fA-F]+") to theme.address,
            Pattern.compile("<[^>]+>") to theme.label
        )

        for ((pattern, color) in patterns) {
            val matcher = pattern.matcher(code)
            while (matcher.find()) {
                ssb.setSpan(
                    ForegroundColorSpan(color),
                    matcher.start(),
                    matcher.end(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return ssb
    }
}
