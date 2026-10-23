package com.mezze.language.ast

import com.mezze.language.ast.hashing.MezzeHashBuilder
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * THE record-alias adoption rule, exercised directly on the pool it is handed.
 *
 * Its uniqueness guard used to count entries, and callers assemble the pool from
 * overlapping sources — emit takes the declaration registry AND the import
 * context, so a `use`d alias appeared TWICE under one content hash. The guard
 * then rejected the only candidate there was, which cost every imported alias
 * its nominal identity (see the interpreter's ImportedAliasAdoptionTest for what
 * that broke at runtime).
 *
 * Here the pool is literal, so the self-duplicate and a genuine rival can be
 * told apart — which the compiler itself cannot show, since a declaration's name
 * is part of its content and two decls that share a shape share a hash.
 */
class RecordAliasAdoptionTest {

    private fun hash(seed: String): Hash = MezzeHashBuilder().also { it.append(seed) }.buildHash()

    private val shape = mapOf("n" to MezzeType.IntType)
    private fun literal() = MezzeType.RecordType(shape)
    private fun alias(name: String, seed: String = name) = Triple(name, hash(seed), shape)

    @Test
    fun `a unique alias is adopted`() {
        val adopted = adoptRecordAlias(literal(), listOf(alias("Boxed")))
        assertEquals(TypeIdentity.declared("Boxed", hash("Boxed")), adopted.identity)
    }

    /** THE regression: one declaration reaching the pool twice is one type. */
    @Test
    fun `the same declaration listed twice is still adopted`() {
        val once = alias("Boxed")
        val adopted = adoptRecordAlias(literal(), listOf(once, once))
        assertEquals(TypeIdentity.declared("Boxed", hash("Boxed")), adopted.identity)
    }

    /** Two distinct declarations of one shape are a real conflict: decline. */
    @Test
    fun `two distinct declarations of one shape are declined`() {
        val adopted = adoptRecordAlias(literal(), listOf(alias("L"), alias("R")))
        assertEquals(TypeIdentity.Anonymous, adopted.identity)
    }

    /**
     * Same NAME, different content hash — the case that fixes the dedupe KEY.
     * Keying on the name would collapse these two into one and adopt whichever
     * came first; identity is the hash, so they stay a conflict.
     */
    @Test
    fun `same name with different hashes is declined`() {
        val adopted = adoptRecordAlias(
            literal(),
            listOf(alias("Thing", seed = "thing-from-left"), alias("Thing", seed = "thing-from-right")),
        )
        assertEquals(TypeIdentity.Anonymous, adopted.identity)
    }

    @Test
    fun `an already-identified record keeps its identity`() {
        val already = MezzeType.RecordType(shape, identity = TypeIdentity.declared("Kept", hash("Kept")))
        val adopted = adoptRecordAlias(already, listOf(alias("Boxed")))
        assertEquals(TypeIdentity.declared("Kept", hash("Kept")), adopted.identity)
    }

    @Test
    fun `an open record is never adopted`() {
        val open = MezzeType.RecordType(shape, rowVar = RowVar("r"))
        val adopted = adoptRecordAlias(open, listOf(alias("Boxed")))
        assertEquals(TypeIdentity.Anonymous, adopted.identity)
    }
}
