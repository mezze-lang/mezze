package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Empty-collection / empty-literal inference under an expected type.
 *
 * The sibling file `EmptySeqsInRecordTest` exercises the same scenarios but asserts
 * NOTHING (println-only bodies) — it only proves the checker doesn't throw. This file
 * complements it with REAL accept/reject assertions:
 *
 *  - an empty seq literal `[]` unifies with an ANNOTATED element type (`let xs: [Int] = []`),
 *  - `[]` flows into a parameter of type `[Str]` at a call site,
 *  - an empty record `{}` is rejected when a caller demands a field on it,
 *  - an empty seq whose element type is left open is later PINNED by a concrete use,
 *    and a conflicting use must be rejected.
 *
 * Seq syntax note (verified against the suite): `[Int]` is the surface sugar for `Seq Int`.
 */
class EmptyLiteralInferenceTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        tc = TypeChecker()
    }

    /** Returns the error message on rejection, or null if the program type-checks. */
    private fun err(src: String): String? =
        try {
            tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true)
            null
        } catch (e: TypeCheckError) {
            e.message
        }

    /** Returns the caught error (for loc/code assertions), or null on success. */
    private fun caught(src: String): TypeCheckError? =
        try {
            tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true)
            null
        } catch (e: TypeCheckError) {
            e
        }

    // ===== `[]` unifies with an annotated element type =====

    @Test fun `empty seq with annotated Int element type checks`() {
        // The annotation `[Int]` pins the element type; `[]` must adopt it.
        assertNull(
            err("""
                let xs: [Int] = []
                xs
            """),
            "`let xs: [Int] = []` must type-check"
        )
    }

    @Test fun `empty seq with annotated Str element type checks`() {
        assertNull(
            err("""
                let xs: [Str] = []
                xs
            """),
            "`let xs: [Str] = []` must type-check"
        )
    }

    @Test fun `non-empty seq contradicting its annotated element type is rejected`() {
        // Guards the accept cases above: the annotation is really being enforced,
        // not silently ignored for empty-adjacent literals.
        val e = err("""
            let xs: [Int] = ["a", "b"]
            xs
        """)
        assertNotNull(e, "`[Str]` literal under `[Int]` annotation must be rejected")
    }

    // ===== `[]` flows into a parameter of a fixed element type =====

    @Test fun `empty seq passed where param expects Seq Str checks`() {
        // The parameter type `[Str]` supplies the expected element type for the `[]` arg.
        assertNull(
            err("""
                let takes = { xs: [Str] } -> xs
                takes { xs = [] }
            """),
            "`[]` passed to a `[Str]` parameter must type-check"
        )
    }

    @Test fun `empty seq passed where param expects Seq Int then a Str element is rejected`() {
        // Once `[]` is fixed to `[Int]` by the parameter, a Str element can't sneak in.
        val e = err("""
            let takes = { xs: [Int] } -> xs
            takes { xs = ["nope"] }
        """)
        assertNotNull(e, "Str element for an `[Int]` parameter must be rejected")
    }

    // ===== empty record `{}` where a field is demanded =====

    @Test fun `field access on an empty record literal is rejected`() {
        // `{}` has no fields; reading `.a` off it must fail — the checker may not
        // invent the field to satisfy the access.
        val e = caught("""
            let r = {}
            r.a
        """)
        assertNotNull(e, "reading `.a` on empty record `{}` must be rejected")
        assertNotNull(e!!.loc, "rejection must carry a source location")
    }

    @Test fun `passing an empty record where a field is demanded is rejected`() {
        // A parameter closed on `{ a: Int }` requires the field; `{}` omits it.
        val e = err("""
            let use_a = { r: { a: Int } } -> r.a
            use_a { r = {} }
        """)
        assertNotNull(e, "empty record `{}` for a `{ a: Int }` parameter must be rejected")
    }

    @Test fun `empty record satisfying a closed empty record annotation checks`() {
        // Sanity counterpart: `{}` IS a valid value of the empty record type.
        assertNull(
            err("""
                let r: {} = {}
                r
            """),
            "`{}` against a `{}` annotation must type-check"
        )
    }

    // ===== empty seq element type pinned later by use =====

    @Test fun `unannotated empty seq pinned by a subsequent Int use checks`() {
        // No annotation up front; the element type is fixed by appending an Int later.
        // Uses stdlib `seq::of { x, xs }` (element `x`, tail `xs`) as the pinning use,
        // matching the pattern in EmptySeqsInRecordTest.
        assertNull(
            err("""
                use std::collections::seq
                let xs = []
                let ys = seq::of { x = 1, xs = xs }
                ys
            """),
            "an empty seq pinned to Int by a later `seq::of` use must type-check"
        )
    }

    @Test fun `unannotated empty seq pinned to Int cannot then take a Str element`() {
        // EXPECTED-CORRECT: a sound checker must reject the conflicting second pin.
        // Once `xs` is fixed to `[Int]` by the first use, prepending a Str is a clash.
        val e = err("""
            use std::collections::seq
            let xs = []
            let a = seq::of { x = 1, xs = xs }
            let b = seq::of { x = "two", xs = xs }
            b
        """)
        assertNotNull(e, "empty seq pinned to Int must reject a later Str element")
    }
}
