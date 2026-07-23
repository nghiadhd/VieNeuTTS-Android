package com.vieneu.engine.tokenizer

/**
 * Tiny recursive-descent JSON parser — just enough to pull `model.vocab` and
 * `model.merges` out of a HuggingFace `tokenizer.json`. Deliberately not
 * `org.json` (Android-stubbed in plain JVM unit tests, so using it here would
 * break the host-side shift-left tests) and not a third-party dependency.
 */
object MiniJson {
    fun parse(text: String): Any? = Parser(text).parseValue()

    private class Parser(private val s: String) {
        var i = 0

        fun parseValue(): Any? {
            skipWs()
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> parseNumber()
            }
        }

        private fun expect(lit: String) {
            require(s.regionMatches(i, lit, 0, lit.length)) { "expected '$lit' at $i" }
            i += lit.length
        }

        private fun parseObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (s[i] == '}') { i++; return map }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                require(s[i] == ':') { "expected ':' at $i" }
                i++
                val value = parseValue()
                map[key] = value
                skipWs()
                when (s[i]) {
                    ',' -> { i++; continue }
                    '}' -> { i++; break }
                    else -> error("expected ',' or '}' at $i")
                }
            }
            return map
        }

        private fun parseArray(): List<Any?> {
            val list = ArrayList<Any?>()
            i++ // [
            skipWs()
            if (s[i] == ']') { i++; return list }
            while (true) {
                list.add(parseValue())
                skipWs()
                when (s[i]) {
                    ',' -> { i++; continue }
                    ']' -> { i++; break }
                    else -> error("expected ',' or ']' at $i")
                }
            }
            return list
        }

        private fun parseString(): String {
            require(s[i] == '"')
            i++
            val sb = StringBuilder()
            while (s[i] != '"') {
                val c = s[i]
                if (c == '\\') {
                    i++
                    when (val esc = s[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = s.substring(i + 1, i + 5)
                            sb.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> error("bad escape \\$esc at $i")
                    }
                    i++
                } else {
                    sb.append(c)
                    i++
                }
            }
            i++ // closing quote
            return sb.toString()
        }

        private fun parseNumber(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }
}

@Suppress("UNCHECKED_CAST")
fun Any?.asObj(): Map<String, Any?> = this as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Any?.asArr(): List<Any?> = this as List<Any?>

fun Any?.asStr(): String = this as String

fun Any?.asNum(): Int = (this as Double).toInt()
