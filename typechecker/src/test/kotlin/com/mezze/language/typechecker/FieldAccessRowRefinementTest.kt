package com.mezze.language.typechecker

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Accessing a field on an un-annotated (polymorphic) parameter ROW-REFINES that
 * parameter: `let getA = { r } -> r.a` is inferred as
 * `{ r: { a: 'b } } -> 'b`, NOT `{ r: 'a } -> ?`. The `.a` access adds a row
 * constraint "the argument is a record that has field `a`" — structural row
 * polymorphism (cf. OCaml object rows / PureScript row types).
 *
 * This is the safety net behind field access on generic params: it must accept a
 * record that HAS the field, reject one that lacks it, reject a non-record, and
 * flow the field's type to the result. Without the row constraint you could read
 * a field off a value that doesn't have it — a soundness hole. These tests pin
 * that the refinement fires and is sound.
 */
class FieldAccessRowRefinementTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun typeOf(name: String, src: String): String? {
        val r = tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true)
        return r.getVariableType(name)?.let { prettyPrintType(it) }
    }
    private fun err(src: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    @Test
    fun `field access refines a bare param to a record-with-that-field`() {
        val t = typeOf("getA", "let getA = { r } -> r.a")
        assertNotNull(t)
        // The param must be a record carrying field `a` (open — extra fields ok),
        // and the result must be that field's type.
        assertTrue(Regex("""\{\s*r:\s*\{\s*a:""").containsMatchIn(t!!),
            "param should be refined to a record with field a, got: $t")
    }

    @Test
    fun `applying to a record that has the field is accepted`() {
        assertNull(err("""
            let getA = { r } -> r.a
            let n = getA { r = { a = 1 } }
        """))
    }

    @Test
    fun `applying to a record that LACKS the field is rejected`() {
        // The soundness case: without row refinement this would read a missing field.
        val e = err("""
            let getA = { r } -> r.a
            let n = getA { r = { b = 1 } }
        """)
        assertNotNull(e, "a record without field a must be rejected")
        assertTrue(e!!.contains("a"), "error should reference the required field a: $e")
    }

    @Test
    fun `the accessed field's type flows to the result`() {
        // getA{r={a="s"}} : Str, so binding it to Int must fail.
        val e = err("""
            let getA = { r } -> r.a
            let n: Int = getA { r = { a = "s" } }
        """)
        assertNotNull(e, "field type must flow: Str result can't satisfy Int")
    }

    @Test
    fun `distinct callsites with distinct field types do not cross-leak`() {
        assertNull(err("""
            let getA = { r } -> r.a
            let n: Int = getA { r = { a = 1 } }
            let s: Str = getA { r = { a = "x" } }
        """), "getA is polymorphic in the field type; two callsites must not unify")
    }

    @Test
    fun `an extra field beyond the accessed one is allowed (open refinement)`() {
        assertNull(err("""
            let getA = { r } -> r.a
            let n = getA { r = { a = 1, b = 2, c = 3 } }
        """), "the refined record is open — extra fields are fine")
    }

    @Test
    fun `accessing two distinct fields refines to a record having both`() {
        // `r.a` and `r.b` together require the arg to carry BOTH a and b; a value
        // with only `a` must be rejected. Return a record of the two accesses so
        // both fields are demanded without relying on any operator.
        val e = err("""
            let pair2 = { r } -> { x = r.a, y = r.b }
            let go = {} -> pair2 { r = { a = 1 } }
        """)
        assertNotNull(e, "using both r.a and r.b means the arg must have a AND b")
    }
}
