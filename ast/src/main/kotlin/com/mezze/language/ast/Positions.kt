package com.mezze.language.ast

import java.io.ByteArrayOutputStream

/**
 * Source positions written BESIDE a value rather than inside it.
 *
 * A declaration is stored under the hash of what it means, and where it sits in
 * a file is no part of that: the same function in another checkout, or three
 * lines further down, is the same declaration. Written inline, its file and
 * line numbers made the bytes under one hash differ from machine to machine.
 *
 * Inside [write], every location ([com.mezze.language.parsedast.Loc],
 * [com.mezze.language.truffleir.SourceInfo], a function's source file) writes
 * nothing of itself into the value and appends itself here instead, in the
 * order it is met; inside [read] each takes the next one back. The walk is the
 * same on both sides, so no index is written. Outside both, locations are
 * written inline as they always were.
 *
 * A type variable's id goes the same way. It is a number from a counter the
 * whole process shares, so it says how much was checked before, not what the
 * declaration is: the value carries the variable's NUMBER WITHIN THE
 * DECLARATION — first met is 0 — which keeps two variables apart exactly as
 * their ids did, and the id itself goes beside it.
 */
object Positions {
    internal class Writer {
        private val out = ByteArrayOutputStream()
        private val files = HashMap<String, Int>()

        fun int(value: Int) {
            // Zigzag, then 7 bits at a time: positions are small and never negative,
            // a synthetic one may be -1.
            var v = (value shl 1) xor (value shr 31)
            while (v and 0x7f.inv() != 0) { out.write((v and 0x7f) or 0x80); v = v ushr 7 }
            out.write(v)
        }

        /** A file by number; its text follows the first time it is met. */
        fun file(path: String) {
            val known = files[path]
            if (known != null) { int(known); return }
            val n = files.size
            files[path] = n
            int(n)
            val bytes = path.toByteArray(Charsets.UTF_8)
            int(bytes.size)
            out.write(bytes)
        }

        private val variables = HashMap<Int, Int>()

        /** The number of the type variable [id] within what is being written; the id itself goes aside. */
        fun variable(id: Int): Int {
            int(id)
            return variables.getOrPut(id) { variables.size }
        }

        fun bytes(): ByteArray = out.toByteArray()
    }

    internal class Reader(private val bytes: ByteArray) {
        private var at = 0
        private val files = ArrayList<String>()

        fun int(): Int {
            var v = 0
            var shift = 0
            while (true) {
                check(at < bytes.size) { "a stored declaration has fewer positions than locations" }
                val b = bytes[at++].toInt() and 0xff
                v = v or ((b and 0x7f) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return (v ushr 1) xor -(v and 1)
        }

        fun file(): String {
            val n = int()
            if (n < files.size) return files[n]
            check(n == files.size) { "a stored declaration names file $n before file ${files.size}" }
            val len = int()
            check(at + len <= bytes.size) { "a stored declaration's file name runs past its positions" }
            return String(bytes, at, len, Charsets.UTF_8).also { at += len; files.add(it) }
        }

        fun done(): Boolean = at == bytes.size
    }

    internal val writing = ThreadLocal<Writer?>()
    internal val reading = ThreadLocal<Reader?>()

    /** Run [body] with locations written aside: its result, and the positions it met. */
    fun <T> write(body: () -> T): Pair<T, ByteArray> {
        val outer = writing.get()
        val writer = Writer()
        writing.set(writer)
        try { return body() to writer.bytes() } finally { writing.set(outer) }
    }

    /** Run [body] reading a value written inside [write], taking its locations from [positions]. */
    fun <T> read(positions: ByteArray, body: () -> T): T {
        val outer = reading.get()
        val reader = Reader(positions)
        reading.set(reader)
        try {
            val value = body()
            check(reader.done()) { "a stored declaration has more positions than locations" }
            return value
        } finally { reading.set(outer) }
    }
}
