package com.mezze.interpreter.standalone

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Minimal DAP wire codec: `Content-Length: N\r\n\r\n<json>` framing (same as
 * LSP) plus a small hand-rolled JSON reader/writer for the ~12 DAP message
 * types. Hand-rolled to avoid adding a JSON dep to the deliberately-slim runtime
 * jar (see memory project_wire_protocol).
 *
 * The JSON model is plain Kotlin: Map<String,Any?> for objects, List<Any?> for
 * arrays, String / Double / Boolean / null for scalars. DAP integers arrive as
 * Double; callers coerce via `(x as Number).toInt()`.
 */
object DapWire {

    // ─── Framing ─────────────────────────────────────────────────────────

    /** Read one framed message, or null at EOF. */
    fun readMessage(input: InputStream): Map<String, Any?>? {
        var contentLength = -1
        // Read headers line-by-line until a blank line.
        while (true) {
            val line = readHeaderLine(input) ?: return null
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                val name = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (name.equals("Content-Length", ignoreCase = true)) {
                    contentLength = value.toIntOrNull() ?: -1
                }
            }
        }
        if (contentLength < 0) return null
        val bytes = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(bytes, read, contentLength - read)
            if (n < 0) return null
            read += n
        }
        val json = String(bytes, StandardCharsets.UTF_8)
        @Suppress("UNCHECKED_CAST")
        return JsonMini.parse(json) as? Map<String, Any?>
    }

    /** Write one framed message. */
    fun writeMessage(out: OutputStream, msg: Map<String, Any?>) {
        val json = JsonMini.write(msg)
        val body = json.toByteArray(StandardCharsets.UTF_8)
        val header = "Content-Length: ${body.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        synchronized(out) {
            out.write(header)
            out.write(body)
            out.flush()
        }
    }

    /** Read a single header line terminated by CRLF (or LF). Null at EOF. */
    private fun readHeaderLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) {
                // Strip a trailing CR.
                if (sb.isNotEmpty() && sb.last() == '\r') sb.deleteCharAt(sb.length - 1)
                return sb.toString()
            }
            sb.append(c.toChar())
        }
    }
}

/**
 * A tiny JSON parser + serializer for the DAP message subset. Objects →
 * LinkedHashMap, arrays → ArrayList, numbers → Double, plus String/Boolean/null.
 * Not a general-purpose JSON lib — just enough for DAP.
 */
internal object JsonMini {

    fun write(value: Any?): String {
        val sb = StringBuilder()
        writeValue(sb, value)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(value.toString())
            is Int -> sb.append(value.toString())
            is Long -> sb.append(value.toString())
            is Double -> {
                // Emit integers without a trailing ".0" so line numbers stay clean.
                if (value == Math.floor(value) && !value.isInfinite()) sb.append(value.toLong().toString())
                else sb.append(value.toString())
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    writeValue(sb, v)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeValue(sb, v)
                }
                sb.append(']')
            }
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
    }

    // ─── Parser ──────────────────────────────────────────────────────────

    fun parse(json: String): Any? = Parser(json).parseTopLevel()

    private class Parser(private val s: String) {
        private var i = 0

        fun parseTopLevel(): Any? {
            skipWs()
            val v = parseValue()
            skipWs()
            return v
        }

        private fun parseValue(): Any? {
            skipWs()
            return when (val c = peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBool()
                'n' -> parseNull()
                else -> if (c == '-' || c in '0'..'9') parseNumber()
                        else throw IllegalStateException("unexpected char '$c' at $i")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { i++; return map }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                val value = parseValue()
                map[key] = value
                skipWs()
                when (val c = next()) {
                    ',' -> continue
                    '}' -> break
                    else -> throw IllegalStateException("expected , or } but got '$c' at ${i - 1}")
                }
            }
            return map
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { i++; return list }
            while (true) {
                list.add(parseValue())
                skipWs()
                when (val c = next()) {
                    ',' -> continue
                    ']' -> break
                    else -> throw IllegalStateException("expected , or ] but got '$c' at ${i - 1}")
                }
            }
            return list
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                when (val c = next()) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = next()) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'u' -> {
                            val hex = s.substring(i, i + 4)
                            i += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Double {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && (s[i] in '0'..'9' || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            return s.substring(start, i).toDouble()
        }

        private fun parseBool(): Boolean {
            return if (s.startsWith("true", i)) { i += 4; true }
            else { require(s.startsWith("false", i)); i += 5; false }
        }

        private fun parseNull(): Any? {
            require(s.startsWith("null", i)); i += 4; return null
        }

        private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun peek(): Char = s[i]
        private fun next(): Char = s[i++]
        private fun expect(c: Char) { if (next() != c) throw IllegalStateException("expected '$c' at ${i - 1}") }
    }
}
