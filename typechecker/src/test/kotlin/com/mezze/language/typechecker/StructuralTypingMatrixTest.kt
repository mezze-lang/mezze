package com.mezze.language.typechecker

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * The open/closed × record/variant × {construct, arg-pass, pattern-match}
 * behaviour matrix, plus generic instantiation soundness — pinned as permanent
 * assertions from the exploratory sweeps. Records-required-fields (#190) and
 * record-pattern exhaustiveness live in their own files; this covers the rest of
 * the matrix so the structural-typing surface can't silently regress.
 */
class StructuralTypingMatrixTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun err(src: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }
    private fun typeOf(name: String, src: String): String? =
        tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true)
            .getVariableType(name)?.let { prettyPrintType(it) }

    // ── CLOSED RECORD ────────────────────────────────────────────────────
    @Test fun `closed record exact fields is accepted`() {
        assertNull(err("type P = { name: Str, age: Int }\nlet p: P = { name = \"A\", age = 1 }"))
    }
    @Test fun `closed record with an extra field is rejected`() {
        assertNotNull(err("type P = { name: Str, age: Int }\nlet p: P = { name = \"A\", age = 1, x = 9 }"))
    }
    @Test fun `closed record missing a field is rejected`() {
        assertNotNull(err("type P = { name: Str, age: Int }\nlet p: P = { name = \"A\" }"))
    }
    @Test fun `closed record arg extra field is rejected`() {
        assertNotNull(err("""
            let f = { p: { name: Str, age: Int } } -> p.name
            let g = {} -> f { p = { name = "A", age = 1, x = 9 } }
        """))
    }

    // ── OPEN RECORD ──────────────────────────────────────────────────────
    @Test fun `open record accepts an extra field`() {
        assertNull(err("type P = { name: Str, age: Int, ... }\nlet p: P = { name = \"A\", age = 1, x = 9 }"))
    }
    @Test fun `a closed value passes to an open param (width subtyping)`() {
        assertNull(err("""
            let f = { p: { name: Str, ... } } -> p.name
            let g = {} -> f { p = { name = "A", age = 1 } }
        """))
    }

    // ── field access on a bare record ────────────────────────────────────
    @Test fun `accessing a field not present in a closed record is rejected`() {
        assertNotNull(err("type P = { a: Int }\nlet f = { p: P } -> p.zzz"))
    }

    // ── CLOSED VARIANT ───────────────────────────────────────────────────
    @Test fun `a closed variant value adopts its type`() {
        assertEquals("Color", typeOf("c", "type Color = | Red, Green, Blue |\nlet c = Red"))
    }
    @Test fun `a closed variant match must be exhaustive`() {
        assertNull(err("""
            type Color = | Red, Green, Blue |
            let name = { c: Color } ->
              match c is
                Red -> "r"
                Green -> "g"
                Blue -> "b"
        """))
    }
    @Test fun `a non-exhaustive closed variant match is rejected`() {
        assertNotNull(err("""
            type Color = | Red, Green, Blue |
            let name = { c: Color } ->
              match c is
                Red -> "r"
                Green -> "g"
        """))
    }
    @Test fun `a wildcard makes a closed variant match exhaustive`() {
        assertNull(err("""
            type Color = | Red, Green, Blue |
            let name = { c: Color } ->
              match c is
                Red -> "r"
                _ -> "other"
        """))
    }
    @Test fun `an unknown constructor in a match is rejected`() {
        assertNotNull(err("""
            type Color = | Red, Green, Blue |
            let name = { c: Color } ->
              match c is
                Red -> "r"
                Green -> "g"
                Blue -> "b"
                Purple -> "p"
        """))
    }
    @Test fun `a variant constructor missing a payload field is rejected`() {
        assertNotNull(err("type Box = | Boxed { a: Int, b: Int } |\nlet x = Boxed { a = 1 }"))
    }
    @Test fun `a variant constructor with an extra payload field is rejected`() {
        assertNotNull(err("type Box = | Boxed { a: Int } |\nlet x = Boxed { a = 1, b = 2 }"))
    }
    @Test fun `a variant payload wrong field type is rejected`() {
        assertNotNull(err("type Box = | Boxed { a: Int } |\nlet x = Boxed { a = \"str\" }"))
    }

    // ── OPEN VARIANT ─────────────────────────────────────────────────────
    @Test fun `an open variant match without a wildcard is rejected`() {
        assertNotNull(err("""
            type Color = | Red, Green, ... |
            let name = { c: Color } ->
              match c is
                Red -> "r"
                Green -> "g"
        """))
    }
    @Test fun `an open variant match with a wildcard is accepted`() {
        assertNull(err("""
            type Color = | Red, Green, ... |
            let name = { c: Color } ->
              match c is
                Red -> "r"
                Green -> "g"
                _ -> "other"
        """))
    }

    // ── RECURSIVE VARIANT (list-like) ────────────────────────────────────
    @Test fun `a recursive variant constructs and matches`() {
        assertNull(err("""
            type IntList = | Nil2, Cons2 { head: Int, tail: IntList } |
            let hd = { xs: IntList } ->
              match xs is
                Nil2 -> 0
                Cons2 { head = h, tail = t } -> h
        """))
    }

    // ── RESULT / OPTION (built-ins) ──────────────────────────────────────
    @Test fun `a Result match handling both arms is accepted`() {
        assertNull(err("""
            let f = { r: Result Int Str } ->
              match r is
                Ok { val = v } -> v
                Err { error = e } -> 0
        """))
    }
    @Test fun `a Result match missing the Err arm is rejected`() {
        assertNotNull(err("""
            let f = { r: Result Int Str } ->
              match r is
                Ok { val = v } -> v
        """))
    }

    // ── GENERIC INSTANTIATION ────────────────────────────────────────────
    @Test fun `a generic identity applied and used consistently is accepted`() {
        assertNull(err("let id = { x: 'a } -> x\nlet n: Int = id { x = 5 }"))
    }
    @Test fun `a generic result used at a mismatched annotation is rejected`() {
        assertNotNull(err("let id = { x: 'a } -> x\nlet s: Str = id { x = 5 }"))
    }
    @Test fun `a heterogeneous seq literal is rejected`() {
        assertNotNull(err("let xs = [1, \"two\", 3]"))
    }
    @Test fun `a generic pair projecting the first field type-checks`() {
        assertNull(err("""
            let fst = { a: 'a, b: 'b } -> a
            let n: Int = fst { a = 1, b = "x" }
        """))
    }
    @Test fun `a variant payload type flows to the match binding`() {
        // Boxed { a: Str }; binding a is Str, so using it as Int must fail.
        assertNotNull(err("""
            type Box = | Boxed { a: Str } |
            let f = { x: Box } ->
              match x is
                Boxed { a = aa } ->
                  do
                    let n: Int = aa
                    n
        """))
    }
}
