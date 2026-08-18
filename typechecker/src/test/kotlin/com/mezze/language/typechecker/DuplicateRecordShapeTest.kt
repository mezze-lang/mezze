package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Two CLOSED record aliases with an identical field-name+type shape are the
 * same structural type wearing two names — rejected, mirroring the variant
 * duplicate-constructor rule. (The nominal "distinct types, same fields" case
 * is `newtype`.) Open records differ because they admit extra fields.
 */
class DuplicateRecordShapeTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun err(b: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(b.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    @Test
    fun `two closed record aliases with the same shape are rejected`() {
        val e = err("""
            type A = { x: Int }
            type B = { x: Int }
            let z = 5
        """)
        assertNotNull(e, "identical closed record aliases must conflict")
        assertTrue(e!!.contains("same shape") || e.contains("structural"),
            "error should explain the structural clash, got: $e")
    }

    @Test
    fun `same shape multi-field is rejected regardless of field order`() {
        val e = err("""
            type Lang = { name: Str, released: Bool }
            type Framework = { released: Bool, name: Str }
            let z = 5
        """)
        assertNotNull(e, "same field set (any order) must conflict")
    }

    @Test
    fun `error suggests newtype for the nominal case`() {
        val full = try {
            tc.typeCheck(parser.parseParsedModule("type A = { x: Int }\ntype B = { x: Int }\nlet z = 5", "t.mz"), Paths.get("."), enablePrelude = true)
            null
        } catch (e: TypeCheckError) { (listOf(e.message.orEmpty()) + e.hints).joinToString(" | ") }
        assertNotNull(full)
        assertTrue(full!!.contains("newtype"), "hint should point to newtype, got: $full")
    }

    // ── things that must STAY legal ──────────────────────────────────────

    @Test
    fun `different field names are fine`() {
        assertNull(err("""
            type A = { x: Int }
            type B = { y: Int }
            let z = 5
        """))
    }

    @Test
    fun `same field names but different types are fine`() {
        assertNull(err("""
            type A = { x: Int }
            type B = { x: Str }
            let z = 5
        """))
    }

    @Test
    fun `open records with the same declared fields are fine (they admit extra fields)`() {
        assertNull(err("""
            type A = { x: Int, ... }
            type B = { x: Int, ... }
            let z = 5
        """), "open records differ by their potential extra fields")
    }

    @Test
    fun `an open and a closed record with the same fields are fine`() {
        assertNull(err("""
            type A = { x: Int }
            type B = { x: Int, ... }
            let z = 5
        """))
    }

    @Test
    fun `a single record alias is obviously fine`() {
        assertNull(err("""
            type Lang = { name: Str, released: Bool }
            let x: Lang = { name = "Mezze", released = False }
        """))
    }
}
