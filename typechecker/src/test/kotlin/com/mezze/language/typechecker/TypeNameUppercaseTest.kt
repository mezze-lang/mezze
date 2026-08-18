package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Type NAMES (alias / record / variant / newtype) must start with an uppercase
 * letter, matching the newtype-constructor rule and how types are written
 * everywhere. Previously `type lang = …` (lowercase) was silently accepted.
 */
class TypeNameUppercaseTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun err(b: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(b.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    /** Message + hints joined, so a test can assert on either. */
    private fun errFull(b: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(b.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { (listOf(e.message.orEmpty()) + e.hints).joinToString(" | ") }

    @Test
    fun `lowercase record-alias type name is rejected`() {
        val e = err("""
            type lang = { name: Str, released: Bool }
            let z = 5
        """)
        assertNotNull(e, "`type lang` (lowercase) must be rejected")
        assertTrue(e!!.contains("uppercase"), "error should mention uppercase, got: $e")
        assertTrue(e.contains("lang"), "error should name the offending type, got: $e")
    }

    @Test
    fun `lowercase variant type name is rejected`() {
        val e = err("""
            type color = | Red, Green, Blue |
            let z = 5
        """)
        assertNotNull(e, "`type color` (lowercase) must be rejected")
        assertTrue(e!!.contains("uppercase"), "got: $e")
    }

    @Test
    fun `lowercase newtype name is rejected`() {
        // A newtype `money = money Int` fails on either the name or the ctor —
        // both must be uppercase.
        val e = err("""
            newtype money = money Int
            let z = 5
        """)
        assertNotNull(e, "lowercase newtype must be rejected")
        assertTrue(e!!.contains("uppercase"), "got: $e")
    }

    @Test
    fun `the suggested fix capitalises the name`() {
        val e = errFull("""
            type lang = { name: Str }
            let z = 5
        """)
        assertNotNull(e)
        assertTrue(e!!.contains("Lang"), "hint should suggest the capitalised name `Lang`, got: $e")
    }

    // ── uppercase names keep working (no false positives) ────────────────

    @Test
    fun `uppercase record-alias type name is accepted`() {
        assertNull(err("""
            type Lang = { name: Str, released: Bool }
            let x: Lang = { name = "Mezze", released = False }
        """), "an uppercase `type Lang` must still typecheck")
    }

    @Test
    fun `uppercase variant type name is accepted`() {
        assertNull(err("""
            type Color = | Red, Green, Blue |
            let c = Red
        """))
    }

    @Test
    fun `uppercase newtype is accepted`() {
        assertNull(err("""
            newtype Money = Money Int
            let z = 5
        """))
    }
}
