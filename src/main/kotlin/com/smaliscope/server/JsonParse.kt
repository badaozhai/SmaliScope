package com.smaliscope.server

/**
 * 极简 JSON 解析。Web 工作台的命令走查询参数，本来不需要解析器；
 * MCP 走 JSON-RPC，请求体是 JSON，才需要读进来。
 * 依然不引序列化库——够用就好，且完全可控。
 */
sealed class Jv {
    object Null : Jv()
    data class Bool(val v: Boolean) : Jv()
    data class Num(val v: Double) : Jv()
    data class Str(val v: String) : Jv()
    data class Arr(val v: List<Jv>) : Jv()
    data class Obj(val v: Map<String, Jv>) : Jv()

    operator fun get(key: String): Jv? = (this as? Obj)?.v?.get(key)

    val string: String? get() = (this as? Str)?.v
    val int: Int? get() = (this as? Num)?.v?.toInt()
    val long: Long? get() = (this as? Num)?.v?.toLong()
    val bool: Boolean? get() = (this as? Bool)?.v
    val list: List<Jv> get() = (this as? Arr)?.v ?: emptyList()

    /** 宽松取字符串：数字/布尔也转成字符串，省得调用方到处判类型。 */
    val asText: String?
        get() = when (this) {
            is Str -> v
            is Num -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
            is Bool -> v.toString()
            else -> null
        }
}

object JsonParse {

    fun parse(text: String): Jv {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        require(p.atEnd) { "JSON 结尾有多余内容（位置 ${p.pos}）" }
        return v
    }

    private class Parser(private val s: String) {
        var pos = 0
        val atEnd: Boolean get() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Jv {
            skipWs()
            require(pos < s.length) { "JSON 意外结束" }
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> Jv.Str(str())
                't' -> { expect("true"); Jv.Bool(true) }
                'f' -> { expect("false"); Jv.Bool(false) }
                'n' -> { expect("null"); Jv.Null }
                else -> if (c == '-' || c.isDigit()) num() else error("非法字符 '$c'（位置 $pos）")
            }
        }

        private fun expect(word: String) {
            require(s.startsWith(word, pos)) { "期望 $word（位置 $pos）" }
            pos += word.length
        }

        private fun obj(): Jv {
            pos++ // {
            val map = LinkedHashMap<String, Jv>()
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return Jv.Obj(map) }
            while (true) {
                skipWs()
                val k = str()
                skipWs()
                require(pos < s.length && s[pos] == ':') { "对象里缺少 ':'（位置 $pos）" }
                pos++
                map[k] = value()
                skipWs()
                require(pos < s.length) { "对象未闭合" }
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return Jv.Obj(map) }
                    else -> error("对象里出现意外字符 '${s[pos]}'（位置 $pos）")
                }
            }
        }

        private fun arr(): Jv {
            pos++ // [
            val out = ArrayList<Jv>()
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return Jv.Arr(out) }
            while (true) {
                out += value()
                skipWs()
                require(pos < s.length) { "数组未闭合" }
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return Jv.Arr(out) }
                    else -> error("数组里出现意外字符 '${s[pos]}'（位置 $pos）")
                }
            }
        }

        private fun str(): String {
            require(pos < s.length && s[pos] == '"') { "期望字符串（位置 $pos）" }
            pos++
            val sb = StringBuilder()
            while (true) {
                require(pos < s.length) { "字符串未闭合" }
                when (val c = s[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(pos < s.length) { "转义未完成" }
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(pos + 4 <= s.length) { "\\u 转义不完整" }
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> error("未知转义 \\$e（位置 ${pos - 1}）")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Jv {
            val start = pos
            if (pos < s.length && s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val text = s.substring(start, pos)
            return Jv.Num(text.toDoubleOrNull() ?: error("非法数字 $text"))
        }
    }
}
