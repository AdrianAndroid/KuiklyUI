/*
 * Tencent is pleased to support the open source community by making KuiklyUI
 * available.
 * Copyright (C) 2025 Tencent. All rights reserved.
 * Licensed under the License of KuiklyUI;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://github.com/Tencent-TDS/KuiklyUI/blob/main/LICENSE
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tencent.kuikly.demo.pages.sftp.viewer.md

/**
 * 轻量代码高亮（纯 Kotlin，commonMain 六端共用；**不使用正则**）。
 *
 * 背景：查看器里 fenced code block 原先整块单色，观感差。这里参考 MarkText/VS Code 的
 * Dark+ 配色，对常见语言做词法着色：关键字 / 字符串 / 注释 / 数字 / 函数 / 类型 / 注解。
 *
 * 为什么不用正则：Kotlin/JS 会把正则编译成 unicode 模式，`[(` 这类字符类会抛
 * `Lone quantifier brackets`（见 AGENTS.md §13 踩坑）。因此全部用字符扫描实现。
 *
 * 高亮按行产出，跨行状态（C 风格块注释、Python 三引号）在 [State] 中延续。
 */
enum class CodeTokenType { PLAIN, KEYWORD, STRING, COMMENT, NUMBER, FUNCTION, TYPE, ANNOTATION, ATTR, PUNCT }

data class CodeToken(val text: String, val type: CodeTokenType)

object CodeHighlighter {

    /** 单个代码块最多高亮的行数（超大代码块其余部分走单色，避免建过多 RichText 视图） */
    const val MAX_HIGHLIGHT_LINES = 300

    private val KEYWORDS = setOf(
        // JS/TS
        "abstract", "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger",
        "default", "delete", "do", "else", "enum", "export", "extends", "false", "finally", "for", "from",
        "function", "get", "if", "implements", "import", "in", "instanceof", "interface", "let", "new",
        "null", "of", "package", "private", "protected", "public", "readonly", "return", "set", "static",
        "super", "switch", "this", "throw", "true", "try", "typeof", "undefined", "var", "void", "while",
        "with", "yield", "as", "type", "declare", "namespace", "keyof", "infer", "satisfies",
        // Kotlin/Java
        "fun", "val", "when", "object", "data", "sealed", "open", "override", "lateinit", "companion",
        "suspend", "inline", "reified", "internal", "init", "constructor", "annotation", "crossinline",
        "noinline", "vararg", "out", "is", "receiver", "by", "where", "actual", "expect", "external",
        "final", "extends", "throws", "synchronized", "volatile", "transient", "native", "strictfp",
        "assert", "boolean", "byte", "char", "double", "float", "int", "long", "short", "abstract",
        // Python
        "def", "elif", "except", "global", "lambda", "nonlocal", "not", "or", "and", "pass", "raise",
        "del", "print", "self", "None", "True", "False", "with", "match",
        // Swift/Go/Rust/C++
        "func", "guard", "defer", "let", "mut", "struct", "impl", "trait", "pub", "use", "mod", "match",
        "loop", "ref", "dyn", "unsafe", "crate", "go", "chan", "select", "map", "range", "defer",
        "auto", "register", "typedef", "sizeof", "namespace", "template", "typename", "public", "private",
        // Shell/SQL
        "echo", "export", "local", "then", "fi", "done", "esac", "select", "insert", "update", "delete",
        "create", "table", "where", "join", "group", "order", "limit", "values", "into", "from", "on",
        "index", "primary", "key", "foreign", "references", "alter", "drop", "distinct", "having",
        // C# / PHP / Ruby / Dart / Scala / Groovy / Lua / Perl / R
        "using", "lock", "fixed", "stackalloc", "params", "readonly", "namespace", "internal", "sealed",
        "function", "end", "module", "require", "puts", "unless", "until", "begin", "rescue", "ensure",
        "final", "late", "required", "mixin", "part", "async", "covariant", "implicit", "lazy", "given",
        "println", "repeat", "elseif", "elsif", "foreach", "endfor", "endif", "print", "sprintf",
        "then", "do", "while", "until", "def", "raises", "include", "extend",
    )

    private val TYPES = setOf(
        "string", "number", "boolean", "any", "unknown", "never", "object", "symbol", "bigint",
        "String", "Number", "Boolean", "Object", "Array", "Map", "Set", "Promise", "Error", "Date",
        "Void", "Unit", "Int", "Long", "Float", "Double", "Char", "Byte", "Short", "List", "MutableList",
        "JSONObject", "JSONArray", "Integer", "Long", "Boolean", "Character", "Byte",
        "str", "int", "float", "bool", "dict", "list", "tuple", "bytes", "void", "auto", "size_t",
        "u8", "u16", "u32", "u64", "i8", "i16", "i32", "i64", "f32", "f64", "usize", "isize",
        "any", "Self", "Type", "Result", "Option", "Vec",
    )

    fun lineCount(code: String): Int {
        if (code.isEmpty()) return 1
        var n = 1
        for (c in code) if (c == '\n') n++
        return n
    }

    /** 高亮 [code]（最多 [MAX_HIGHLIGHT_LINES] 行），返回「行 → 该行 token 列表」。 */
    fun highlight(lang: String, code: String, maxLines: Int = MAX_HIGHLIGHT_LINES): List<List<CodeToken>> {
        val l = normalize(lang)
        val out = ArrayList<List<CodeToken>>()
        val st = State()
        val buf = StringBuilder()
        val toks = ArrayList<CodeToken>()
        var i = 0
        val n = code.length

        fun flushPlain() {
            if (buf.isNotEmpty()) { toks.add(CodeToken(buf.toString(), CodeTokenType.PLAIN)); buf.clear() }
        }
        fun emit(type: CodeTokenType, text: String) {
            if (text.isEmpty()) return
            flushPlain()
            toks.add(CodeToken(text, type))
        }
        fun endLine() {
            flushPlain()
            out.add(if (toks.isEmpty()) listOf(CodeToken("", CodeTokenType.PLAIN)) else ArrayList(toks))
            toks.clear()
        }

        while (i < n) {
            if (code[i] == '\n') {
                endLine()
                if (out.size >= maxLines) return out
                i++
                continue
            }
            val consumed = scanOne(l, code, i, n, st, buf, { t, s -> emit(t, s) })
            if (consumed <= 0) { buf.append(code[i]); i++ } else i += consumed
        }
        endLine()
        return out
    }

    /** 剩余（超出高亮上限）的纯文本 */
    fun tailPlain(code: String, fromLine: Int): String {
        if (fromLine <= 0) return code
        var line = 0
        var i = 0
        val n = code.length
        while (i < n && line < fromLine) { if (code[i] == '\n') line++; i++ }
        return if (i < n) code.substring(i) else ""
    }

    private class State {
        var blockComment = false
        var triple = false
    }

    /** 语言别名归一（`ts`→`typescript`、`c++`→`cpp`、`yml`→`yaml` …），保证常见写法都能被识别。 */
    private fun normalize(lang: String): String {
        val raw = lang.trim().lowercase().substringBefore(' ').substringBefore('{').substringBefore(',').trim()
        return when (raw) {
            "js", "javascript", "mjs", "cjs", "node" -> "javascript"
            "ts" -> "typescript"
            "kt", "kts" -> "kotlin"
            "py", "python3" -> "python"
            "rb" -> "ruby"
            "rs" -> "rust"
            "golang" -> "go"
            "c++", "cc", "cxx", "hpp", "hh", "hxx" -> "cpp"
            "c#", "cs" -> "csharp"
            "objc", "objective-c", "objectivec", "mm" -> "objectivec"
            "sh", "shell", "zsh", "ksh" -> "bash"
            "yml" -> "yaml"
            "htm", "xhtml" -> "html"
            "svg" -> "xml"
            "dockerfile" -> "dockerfile"
            "makefile", "mk" -> "makefile"
            "sass", "less" -> "scss"
            "md" -> "markdown"
            else -> raw
        }
    }

    private fun isMarkup(l: String): Boolean = l == "html" || l == "xml" || l == "vue" || l == "svg"
    private fun isCss(l: String): Boolean = l == "css" || l == "scss"

    private fun lineComment(l: String): String? = when {
        isMarkup(l) || isCss(l) -> null
        l == "json" -> null
        l == "python" || l == "ruby" || l == "yaml" || l == "bash" || l == "perl" ||
            l == "r" || l == "toml" || l == "ini" || l == "conf" || l == "properties" ||
            l == "makefile" || l == "dockerfile" -> "#"
        l == "sql" || l == "lua" || l == "haskell" || l == "elm" -> "--"
        l == "php" -> "//"
        else -> "//"
    }

    private fun blockComment(l: String): Boolean = when {
        l == "python" || l == "sql" || l == "bash" || l == "yaml" || l == "json" ||
            l == "makefile" || l == "properties" -> false
        else -> true
    }

    private fun backtickStrings(l: String): Boolean =
        l == "javascript" || l == "typescript" || l == "jsx" || l == "tsx" || l == "vue"

    private fun tripleQuote(l: String): Boolean = l == "python" || l == "kotlin" || l == "swift"

    /**
     * 从 [i] 起扫描一个 token；返回消费的字符数。
     * - 识别出的 token 经 [emit] 输出（调用方会先 flush 普通字符缓冲，保证顺序）；
     * - 普通字符（空白/标点）追加到 [buf]，与相邻普通字符合并为一个 PLAIN token。
     */
    private fun scanOne(
        lang: String,
        code: String,
        i: Int,
        n: Int,
        st: State,
        buf: StringBuilder,
        emit: (CodeTokenType, String) -> Unit,
    ): Int {
        // 跨行块注释 / 三引号
        if (st.blockComment) {
            val end = code.indexOf("*/", i)
            if (end < 0) { emit(CodeTokenType.COMMENT, code.substring(i, n)); return n - i }
            emit(CodeTokenType.COMMENT, code.substring(i, end + 2)); st.blockComment = false
            return end + 2 - i
        }
        if (st.triple) {
            val end = code.indexOf("\"\"\"", i)
            if (end < 0) { emit(CodeTokenType.STRING, code.substring(i, n)); return n - i }
            emit(CodeTokenType.STRING, code.substring(i, end + 3)); st.triple = false
            return end + 3 - i
        }

        val lc = lineComment(lang)
        if (lc != null && code.startsWith(lc, i)) {
            val end = code.indexOf('\n', i).let { if (it < 0) n else it }
            emit(CodeTokenType.COMMENT, code.substring(i, end))
            return end - i
        }
        if ((lang.startsWith("html") || lang.startsWith("xml")) && code.startsWith("<!--", i)) {
            val end = code.indexOf("-->", i)
            val stop = if (end < 0) n else end + 3
            emit(CodeTokenType.COMMENT, code.substring(i, stop))
            return stop - i
        }
        if (blockComment(lang) && code.startsWith("/*", i)) {
            emit(CodeTokenType.COMMENT, "/*"); st.blockComment = true; return 2
        }
        if (tripleQuote(lang) && code.startsWith("\"\"\"", i)) {
            emit(CodeTokenType.STRING, "\"\"\"")   // 起始三引号也要着色输出，否则看起来缺引号
            st.triple = true
            return 3
        }

        val c = code[i]
        // 标记语言：`<tag attr="v">` 整体着色
        if (isMarkup(lang) && c == '<') return scanMarkupTag(code, i, n, emit)
        // CSS：`#rrggbb` 颜色值
        if (isCss(lang) && c == '#' && i + 1 < n && isHex(code[i + 1])) {
            var j = i + 1
            while (j < n && isHex(code[j])) j++
            emit(CodeTokenType.NUMBER, code.substring(i, j))
            return j - i
        }
        if (c == '"' || c == '\'' || (c == '`' && backtickStrings(lang))) {
            var j = i + 1
            while (j < n) {
                val ch = code[j]
                if (ch == '\\' && j + 1 < n) { j += 2; continue }
                if (ch == c) { j++; break }
                if (ch == '\n') break
                j++
            }
            emit(CodeTokenType.STRING, code.substring(i, j))
            return j - i
        }
        if (c.isDigit() || (c == '.' && i + 1 < n && code[i + 1].isDigit())) {
            var j = i
            while (j < n && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_' || code[j] == '\'')) j++
            emit(CodeTokenType.NUMBER, code.substring(i, j))
            return j - i
        }
        if (c.isLetter() || c == '_' || c == '$' || c == '@') {
            var j = i
            while (j < n && (code[j].isLetterOrDigit() || code[j] == '_' || code[j] == '$')) j++
            val word = code.substring(i, j)
            emit(classify(lang, word, code, j), word)
            return j - i
        }
        buf.append(c)
        return 1
    }

    private fun isHex(c: Char): Boolean =
        c.isDigit() || (c in 'a'..'f') || (c in 'A'..'F')

    /** 扫描一个 `<tag …>`（含闭合 `</tag>` 与自闭合 `/>`）：标签名=KEYWORD，属性=ATTR，值=STRING。 */
    private fun scanMarkupTag(code: String, i: Int, n: Int, emit: (CodeTokenType, String) -> Unit): Int {
        var j = i
        if (code.startsWith("</", j)) { emit(CodeTokenType.PUNCT, "</"); j += 2 }
        else { emit(CodeTokenType.PUNCT, "<"); j += 1 }
        val nameStart = j
        while (j < n && (code[j].isLetterOrDigit() || code[j] == '-' || code[j] == ':' || code[j] == '_')) j++
        if (j > nameStart) emit(CodeTokenType.KEYWORD, code.substring(nameStart, j))
        val buf = StringBuilder()
        fun flush() { if (buf.isNotEmpty()) { emit(CodeTokenType.PLAIN, buf.toString()); buf.clear() } }
        while (j < n && code[j] != '>' && code[j] != '\n') {
            val ch = code[j]
            when {
                ch == '"' || ch == '\'' -> {
                    flush()
                    var k = j + 1
                    while (k < n && code[k] != ch && code[k] != '\n') { if (code[k] == '\\' && k + 1 < n) k += 2 else k++ }
                    if (k < n && code[k] == ch) k++
                    emit(CodeTokenType.STRING, code.substring(j, k)); j = k
                }
                ch.isLetter() || ch == '_' || ch == ':' || ch == '@' -> {
                    flush()
                    var k = j
                    while (k < n && (code[k].isLetterOrDigit() || code[k] == '-' || code[k] == '_' || code[k] == ':' || code[k] == '@')) k++
                    emit(CodeTokenType.ATTR, code.substring(j, k)); j = k
                }
                ch == '=' -> { flush(); emit(CodeTokenType.PUNCT, "="); j++ }
                else -> { buf.append(ch); j++ }
            }
        }
        flush()
        if (j < n && code[j] == '>') { emit(CodeTokenType.PUNCT, ">"); j++ }
        return j - i
    }

    private fun classify(lang: String, word: String, code: String, end: Int): CodeTokenType {
        if (word.startsWith("@")) return CodeTokenType.ANNOTATION
        val lower = word.lowercase()
        if (KEYWORDS.contains(lower)) return CodeTokenType.KEYWORD
        if (TYPES.contains(word) || TYPES.contains(lower)) return CodeTokenType.TYPE
        // 函数调用：后面第一个非空白字符是 '('
        var j = end
        while (j < code.length && (code[j] == ' ' || code[j] == '\t')) j++
        if (j < code.length && code[j] == '(') return CodeTokenType.FUNCTION
        // CSS/SCSS：属性名（`color:` / `background-color:`）
        if (isCss(lang) && j < code.length && code[j] == ':') return CodeTokenType.ATTR
        // 大写开头视为类型/类名（非纯数字）
        if (word.length > 1 && word[0].isUpperCase()) return CodeTokenType.TYPE
        return CodeTokenType.PLAIN
    }
}
