package com.mezze.language.typechecker

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Dimension 4 — FORWARD REFS + MUTUAL RECURSION (S6.1 / #366, anchor #126).
 *
 * Top-level decls are dependency-ordered. A forward reference must not collapse
 * its callee to a monotype prematurely, nor leak an un-generalized 'a. Mutually
 * recursive decls must infer without 'a leaks. This file pins the inferred TYPE
 * of forward-referenced and mutually-recursive decls, and pins the #126
 * use-before-def polymorphic-instantiation regression (keyed on type where
 * possible, else on accept/reject).
 */
class ForwardRefRecursionTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun typeOf(name: String, src: String): String? =
        tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true)
            .getVariableType(name)?.let { prettyPrintType(it) }
    private fun err(src: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    // ── #126: a forward-referenced polymorphic fn instantiates at two types ─

    @Test fun `forward-referenced poly fn instantiates at two types (#126)`() {
        assertNull(err("""
            let useIt = {} -> do
              let a: Int = later { x = 1 }
              let b: Str = later { x = "y" }
              a
            let later = { x: 'a } -> x
        """))
    }

    @Test fun `a forward-referenced identity keeps its polymorphic scheme (#126, type-keyed)`() {
        // `later` is used before it is defined; it must still infer the full
        // polymorphic scheme, not a monotype pinned by the first use.
        assertEquals("{ x: 'a } -> 'a", typeOf("later", """
            let useIt = {} -> later { x = 1 }
            let later = { x } -> x
        """))
    }

    // ── mutual recursion: no 'a leak, correct concrete inference ─────────

    @Test fun `mutually recursive predicates infer a concrete monotype`() {
        assertEquals("{ n: Int } -> Bool", typeOf("isEven", """
            let isEven = { n: Int } -> if n == 0 then True else isOdd { n = n - 1 }
            let isOdd = { n: Int } -> if n == 0 then False else isEven { n = n - 1 }
        """))
    }

    @Test fun `a forward reference through a poly callee preserves polymorphism`() {
        // `wrap` forwards to `unwrap` (defined later); `unwrap` is the identity,
        // so `wrap : { x: 'a } -> 'a`. A premature-monomorphization bug would
        // collapse this to a monotype.
        assertEquals("{ x: 'a } -> 'a", typeOf("wrap", """
            let wrap = { x: 'a } -> unwrap { y = x }
            let unwrap = { y: 'b } -> y
        """))
    }

    // ── the poly callee is still usable at two types from another caller ─

    @Test fun `a forwarded poly callee is instantiable at two types across callers`() {
        assertNull(err("""
            let one = {} -> do
              let a: Int = unwrap { y = 1 }
              a
            let two = {} -> do
              let b: Str = unwrap { y = "x" }
              b
            let unwrap = { y } -> y
        """))
    }
}
