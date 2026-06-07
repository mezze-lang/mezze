package com.mezze.interpreter.nodes.builtin.dsl

import com.mezze.datastructures.seq.MezzeSeq
import com.mezze.interpreter.values.MezzeVariant
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary

/**
 * Helper functions originally defined as `private static` inside
 * `NativeDispatch.java`. Moved here so the DSL-side `*Ops.kt` files can
 * call them without duplication. Bodies are bit-for-bit copies of the
 * NativeDispatch originals (with the same `@TruffleBoundary` annotations
 * preserved) so behaviour is identical across the legacy and scalar-ABI
 * paths.
 *
 * `NativeDispatch` keeps its own private copies for now — until step 3
 * of the migration plan deletes the legacy `HANDLERS` map entirely.
 * After that point this file is the single source of truth.
 */
object NativeHelpers {

    // ==================== Int/Float/Nat parsing ====================

    @TruffleBoundary
    fun intFromString(s: String): MezzeVariant = try {
        MezzeVariant.some(java.lang.Long.parseLong(s.trim()))
    } catch (e: NumberFormatException) {
        MezzeVariant.none()
    }

    @TruffleBoundary
    fun floatFromString(s: String): MezzeVariant = try {
        MezzeVariant.some(java.lang.Double.parseDouble(s.trim()))
    } catch (e: NumberFormatException) {
        MezzeVariant.none()
    }

    // ==================== String slicing / search ====================

    @TruffleBoundary
    fun stringSubstring(s: String, start: Long, end: Long): String {
        val startIdx = Math.max(0, Math.min(start, s.length.toLong())).toInt()
        val endIdx = Math.max(0, Math.min(end, s.length.toLong())).toInt()
        if (startIdx >= endIdx) return ""
        return s.substring(startIdx, endIdx)
    }

    @TruffleBoundary
    fun stringCharAt(s: String, i: Long): MezzeVariant {
        if (i < 0 || i >= s.length) return MezzeVariant.none()
        return MezzeVariant.some(s[i.toInt()].toString())
    }

    @TruffleBoundary
    fun stringIndexOf(s: String, sub: String): MezzeVariant {
        val idx = s.indexOf(sub)
        return if (idx >= 0) MezzeVariant.some(idx.toLong()) else MezzeVariant.none()
    }

    @TruffleBoundary
    fun stringLastIndexOf(s: String, sub: String): MezzeVariant {
        val idx = s.lastIndexOf(sub)
        return if (idx >= 0) MezzeVariant.some(idx.toLong()) else MezzeVariant.none()
    }

    @TruffleBoundary
    fun stringSplit(s: String, delimiter: String): MezzeSeq<Any> {
        val parts = s.split(java.util.regex.Pattern.quote(delimiter).toRegex())
        return MezzeSeq.fromArray(parts.toTypedArray())
    }

    @TruffleBoundary
    fun stringJoin(strings: MezzeSeq<*>, separator: String): String {
        val sb = StringBuilder()
        val n = strings.size()
        for (i in 0 until n) {
            if (i > 0) sb.append(separator)
            sb.append(strings.get(i) as String)
        }
        return sb.toString()
    }

    // ==================== String chunking ====================

    @TruffleBoundary
    fun stringLines(s: String): MezzeSeq<Any> {
        val lines = s.split("\n", limit = -1)
        return MezzeSeq.fromArray(lines.toTypedArray())
    }

    @TruffleBoundary
    fun stringWords(s: String): MezzeSeq<Any> {
        val trimmed = s.trim()
        if (trimmed.isEmpty()) return MezzeSeq.empty<Any>()
        val words = trimmed.split("\\s+".toRegex())
        return MezzeSeq.fromArray(words.toTypedArray())
    }

    @TruffleBoundary
    fun stringChars(s: String): MezzeSeq<Any> {
        val out = arrayOfNulls<Any>(s.length)
        for (i in 0 until s.length) out[i] = s[i].toString()
        @Suppress("UNCHECKED_CAST")
        return MezzeSeq.fromArray(out as Array<Any>)
    }

    // ==================== Padding ====================

    @TruffleBoundary
    fun padLeft(s: String, width: Long, pad: String): String {
        if (s.length >= width) return s
        val padding = if (pad.isEmpty()) " " else pad
        val sb = StringBuilder()
        while (sb.length + s.length < width) sb.append(padding)
        return sb.substring(0, Math.max(0, width.toInt() - s.length)) + s
    }

    @TruffleBoundary
    fun padRight(s: String, width: Long, pad: String): String {
        if (s.length >= width) return s
        val padding = if (pad.isEmpty()) " " else pad
        val sb = StringBuilder(s)
        while (sb.length < width) sb.append(padding)
        return sb.substring(0, width.toInt())
    }

    // ==================== Case conversion ====================

    @TruffleBoundary
    fun toCamelCase(s: String): String {
        if (s.isEmpty()) return s
        val result = StringBuilder()
        var capitalizeNext = false
        for (c in s) {
            if (c == '_' || c == '-' || c == ' ') {
                capitalizeNext = true
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(c))
                capitalizeNext = false
            } else {
                result.append(Character.toLowerCase(c))
            }
        }
        return result.toString()
    }

    @TruffleBoundary
    fun toSnakeCase(s: String): String {
        if (s.isEmpty()) return s
        val result = StringBuilder()
        for (i in 0 until s.length) {
            val c = s[i]
            when {
                Character.isUpperCase(c) -> {
                    if (i > 0) result.append('_')
                    result.append(Character.toLowerCase(c))
                }
                c == '-' || c == ' ' -> result.append('_')
                else -> result.append(c)
            }
        }
        return result.toString()
    }

    @TruffleBoundary
    fun toKebabCase(s: String): String {
        if (s.isEmpty()) return s
        val result = StringBuilder()
        for (i in 0 until s.length) {
            val c = s[i]
            when {
                Character.isUpperCase(c) -> {
                    if (i > 0) result.append('-')
                    result.append(Character.toLowerCase(c))
                }
                c == '_' || c == ' ' -> result.append('-')
                else -> result.append(c)
            }
        }
        return result.toString()
    }

    @TruffleBoundary
    fun toScreamingSnakeCase(s: String): String = toSnakeCase(s).uppercase()

    // ==================== URL encode/decode ====================

    /**
     * Percent-encode a single URL component. Matches `encodeURIComponent`
     * (RFC 3986 unreserved set: A-Z a-z 0-9 - _ . ~). Spaces become "%20",
     * NOT "+" — the latter is form-encoding, not URL component encoding.
     */
    @TruffleBoundary
    fun urlEncodeComponent(s: String): String = try {
        java.net.URLEncoder
            .encode(s, java.nio.charset.StandardCharsets.UTF_8)
            .replace("+", "%20")
            .replace("%7E", "~")
            .replace("*", "%2A")  // URLEncoder leaves * alone; RFC says encode
    } catch (e: Exception) { s }

    @TruffleBoundary
    fun urlDecodeComponent(s: String): String = try {
        java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8)
    } catch (e: Exception) { s }

    // ==================== Glob ====================

    /**
     * Custom glob matching optimised for both file paths and URL patterns:
     *   `**`        — zero or more path segments (matches `/`)
     *   `*`         — any chars except `/`
     *   `?`         — any single char except `/`
     *   `[abc]`     — character class
     *   `[!abc]` / `[^abc]` — negated character class
     */
    @TruffleBoundary
    fun globMatch(pattern: String, s: String): Boolean {
        val regex = StringBuilder()
        regex.append("^")
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '*' -> {
                    if (i + 1 < pattern.length && pattern[i + 1] == '*') {
                        if (i + 2 < pattern.length && pattern[i + 2] == '/') {
                            regex.append("(?:[^/]*/)*")
                            i += 3
                        } else {
                            regex.append(".*")
                            i += 2
                        }
                    } else {
                        regex.append("[^/]*")
                        i++
                    }
                }
                c == '?' -> { regex.append("[^/]"); i++ }
                c == '[' -> {
                    val end = pattern.indexOf(']', i)
                    if (end > i) {
                        val charClass = pattern.substring(i + 1, end)
                        if (charClass.startsWith("!") || charClass.startsWith("^")) {
                            regex.append("[^").append(charClass.substring(1)).append("]")
                        } else {
                            regex.append("[").append(charClass).append("]")
                        }
                        i = end + 1
                    } else {
                        regex.append("\\[")
                        i++
                    }
                }
                c == '.' || c == '+' || c == '(' || c == ')' || c == '{' || c == '}'
                    || c == '^' || c == '$' || c == '|' || c == '\\' -> {
                    regex.append('\\').append(c); i++
                }
                else -> { regex.append(c); i++ }
            }
        }
        regex.append('$')
        return s.matches(regex.toString().toRegex())
    }

    // ==================== Int helpers (pow / gcd / lcm) ====================
    // Mirror NativeDispatch's private versions; also used by IntOps.

    fun intPow(base0: Long, exp0: Long): Long {
        if (exp0 < 0) return 0
        var result = 1L
        var b = base0
        var e = exp0
        while (e > 0) {
            if ((e and 1L) == 1L) result = Math.multiplyExact(result, b)
            b = Math.multiplyExact(b, b)
            e = e shr 1
        }
        return result
    }

    fun intGcd(a0: Long, b0: Long): Long {
        var a = Math.abs(a0); var b = Math.abs(b0)
        while (b != 0L) { val t = b; b = a % b; a = t }
        return a
    }

    fun intLcm(a: Long, b: Long): Long {
        if (a == 0L || b == 0L) return 0L
        return Math.abs(a / intGcd(a, b) * b)
    }
}
