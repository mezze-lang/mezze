package com.mezze.language.codebase

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * How the SQLite store keeps serialized trees: deflated.
 *
 * A typed tree is almost all repeated text, not data. Every node carries the
 * full class name of its type (`com.mezze.language.ast.MezzeType.RecordType`),
 * every field its name, every hash 128 hex characters, every location its file
 * path. Deflate takes a typed declaration from 23 KB to about 2 KB, and the
 * stdlib's typed modules from 46 MB to 3.6 MB (inflating all 76 costs ~50 ms,
 * against decoding the same 46 MB of CBOR, which every load already does).
 *
 * A storage concern of this backend only: callers put and get the serialized
 * bytes, and nothing above the store knows. Every column it covers is written
 * through [pack] and read through [unpack]; the schema version separates this
 * format from the uncompressed one before it.
 */
internal object StoredBytes {
    fun pack(bytes: ByteArray?): ByteArray? {
        if (bytes == null) return null
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            deflater.setInput(bytes)
            deflater.finish()
            val out = ByteArrayOutputStream(bytes.size / 4 + 64)
            val buf = ByteArray(16 * 1024)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    fun unpack(bytes: ByteArray?): ByteArray? {
        if (bytes == null) return null
        val inflater = Inflater()
        try {
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream(bytes.size * 8)
            val buf = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    error("a stored blob is truncated or not deflated (${bytes.size} bytes)")
                }
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }
}
