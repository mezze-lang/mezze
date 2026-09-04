package com.mezze.language.typechecker.hashing

import com.mezze.language.ast.hashing.MezzeHash
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hex rendering is table-driven rather than String.format, for speed: it ran
 * 64 times per hash and held 68% of the samples in a typecheck profile.
 *
 * Hashes are the store's primary key, so a one-character difference would
 * repartition the whole codebase. These pin the new output against the
 * formatter it replaced.
 */
class MezzeHashHexTest {
    private fun reference(bytes: ByteArray, n: Int) =
        bytes.take(n).joinToString("") { String.format("%02x", it) }

    @Test
    fun `matches String format across every byte value`() {
        // Every byte 0..255, so the sign bit and the 0x0f/0x10 boundary are
        // covered rather than assumed.
        for (v in 0..255) {
            val bytes = ByteArray(MezzeHash.HASH_SIZE_BYTES) { v.toByte() }
            val h = MezzeHash.fromRawBytes(bytes)
            assertEquals(reference(bytes, bytes.size), h.toFullHexString(), "full hex, byte $v")
            assertEquals("#" + reference(bytes, MezzeHash.DISPLAY_BYTES), h.toString(), "toString, byte $v")
        }
    }

    @Test
    fun `matches String format for a mixed pattern`() {
        val bytes = ByteArray(MezzeHash.HASH_SIZE_BYTES) { (it * 7 + 3).toByte() }
        val h = MezzeHash.fromRawBytes(bytes)
        assertEquals(reference(bytes, bytes.size), h.toFullHexString())
        assertEquals("#" + reference(bytes, MezzeHash.DISPLAY_BYTES), h.toString())
    }

    @Test
    fun `full hex is two lower-case chars per byte`() {
        val h = MezzeHash.fromRawBytes(ByteArray(MezzeHash.HASH_SIZE_BYTES) { 0xAB.toByte() })
        assertEquals(MezzeHash.HASH_SIZE_BYTES * 2, h.toFullHexString().length)
        assertEquals("ab".repeat(MezzeHash.HASH_SIZE_BYTES), h.toFullHexString())
    }
}
