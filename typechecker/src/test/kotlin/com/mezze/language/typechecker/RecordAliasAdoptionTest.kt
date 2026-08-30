package com.mezze.language.typechecker

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.ast.prettyPrintTypeWithAliases
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Exact-match structural→alias ADOPTION (Strategy 1, labeling):
 *
 *   type Lang = { name: Str, released: Bool }
 *   let x = { name = "Mezze", released = False }   ⇒   x : Lang
 *
 * When a bare record literal's inferred CLOSED shape exactly matches an in-scope
 * closed record alias (same field NAMES and TYPES), the value is LABELLED with
 * that alias name — it reads as `Lang` in hover / errors / signatures. It is a
 * label only: the type is structurally the same record, so `x` still passes
 * anywhere the bare record — OPEN or closed — is accepted. Adoption respects
 * `pub`/import scoping (only in-scope aliases). Same-shape collisions can't
 * happen (rejected at declaration, see [DuplicateRecordShapeTest]).
 */
class RecordAliasAdoptionTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun typeOf(name: String, b: String): String? {
        val r = tc.typeCheck(parser.parseParsedModule(b.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true)
        return r.getVariableType(name)?.let { prettyPrintTypeWithAliases(it, tc.getTypeAliases()) }
    }

    // RAW print — NO alias resolution. A record that merely PRINTS as `Lang` via
    // alias-aware printing but carries `TypeIdentity.Anonymous` would print
    // structurally here, so this catches an adoption that only lives at display
    // time (which is invisible to `typeOf` and is exactly how the record→alias
    // adoption silently regressed under the transparent-alias refactor, breaking
    // ability dispatch on the value). A truly adopted record prints its Declared
    // name even without the alias table.
    private fun rawTypeOf(name: String, b: String): String? {
        val r = tc.typeCheck(parser.parseParsedModule(b.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true)
        return r.getVariableType(name)?.let { prettyPrintType(it) }
    }
    private fun err(b: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(b.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    private val lang = "type Lang = { name: Str, released: Bool }"

    // ── the adoption itself ──────────────────────────────────────────────

    @Test
    fun `a bare literal exactly matching a closed alias adopts its name`() {
        assertEquals("Lang", typeOf("x", "$lang\nlet x = { name = \"Mezze\", released = False }"))
    }

    @Test
    fun `adoption stamps the nominal identity, not just the display label`() {
        // The raw type (no alias resolution) must ALREADY read as `Lang` — the
        // record carries `Declared(Lang)`, which is what makes ability dispatch on
        // the value resolve `impl … for Lang`. If it only printed `Lang` via the
        // alias table, this would come back structural (`{ name: Str, ... }`).
        assertEquals("Lang", rawTypeOf("x", "$lang\nlet x = { name = \"Mezze\", released = False }"))
    }

    @Test
    fun `an explicit annotation still names it`() {
        assertEquals("Lang", typeOf("x", "$lang\nlet x: Lang = { name = \"Mezze\", released = False }"))
    }

    @Test
    fun `field order does not matter`() {
        assertEquals("Lang", typeOf("x", "$lang\nlet x = { released = True, name = \"M\" }"))
    }

    // ── still structurally compatible (the critical property) ────────────

    @Test
    fun `a labelled value passes where an OPEN record with the same fields is expected`() {
        assertNull(err("""
            $lang
            let x = { name = "M", released = False }
            let takesOpen = { r: { name: Str, released: Bool, ... } } -> r.name
            let useIt = {} -> takesOpen { r = x }
        """), "a Lang-labelled value must still pass to an open record of the same shape")
    }

    @Test
    fun `a labelled value passes where a bare CLOSED record is expected`() {
        assertNull(err("""
            $lang
            let x = { name = "M", released = False }
            let takesClosed = { r: { name: Str, released: Bool } } -> r.name
            let useIt = {} -> takesClosed { r = x }
        """))
    }

    // ── no false adoption ────────────────────────────────────────────────

    @Test
    fun `an extra field means no adoption (stays structural)`() {
        val t = typeOf("x", "$lang\nlet x = { name = \"M\", released = False, stars = 5 }")
        assertNotEquals("Lang", t)
        assertTrue(t!!.contains("stars"), "should stay the structural super-shape, got: $t")
    }

    @Test
    fun `a missing field means no adoption`() {
        val t = typeOf("x", "$lang\nlet x = { name = \"M\" }")
        assertNotEquals("Lang", t)
    }

    @Test
    fun `a differing field type means no adoption`() {
        val t = typeOf("x", "$lang\nlet x = { name = \"M\", released = 1 }")
        assertNotEquals("Lang", t)
    }

    @Test
    fun `an OPEN alias is not adopted by a bare literal`() {
        // Open aliases admit extra fields; a plain closed literal is not one.
        val t = typeOf("x", "type P = { name: Str, ... }\nlet x = { name = \"M\" }")
        assertNotEquals("P", t)
    }

    // ── cross-module: pub + import required ──────────────────────────────

    @Test
    fun `an imported closed alias is adopted`() {
        val dir = Files.createTempDirectory("adopt_xmod")
        fun mod(n: String, s: String) = Files.writeString(dir.resolve("$n.mz"), s.trimIndent())
        mod("shapes", "pub type Lang = { name: Str, released: Bool }")
        mod("user", """
            use shapes::{ Lang }
            pub let x = { name = "M", released = False }
        """)
        val r = tc.typeCheck(parser.parseParsedModule(Files.readString(dir.resolve("user.mz")), "user.mz"), dir)
        assertEquals("Lang", r.getVariableType("x")?.let { prettyPrintTypeWithAliases(it, tc.getTypeAliases()) },
            "an imported alias should be adopted")
    }

    @Test
    fun `an un-imported alias is NOT adopted (stays structural)`() {
        val dir = Files.createTempDirectory("adopt_xmod2")
        fun mod(n: String, s: String) = Files.writeString(dir.resolve("$n.mz"), s.trimIndent())
        mod("shapes2", "pub type Lang = { name: Str, released: Bool }")
        // user does NOT `use shapes2::{ Lang }` — Lang is out of scope here.
        mod("user2", "pub let x = { name = \"M\", released = False }")
        val r = tc.typeCheck(parser.parseParsedModule(Files.readString(dir.resolve("user2.mz")), "user2.mz"), dir)
        val t = r.getVariableType("x")?.let { prettyPrintType(it) }
        assertNotEquals("Lang", t, "an un-imported alias must not be adopted")
    }
}
