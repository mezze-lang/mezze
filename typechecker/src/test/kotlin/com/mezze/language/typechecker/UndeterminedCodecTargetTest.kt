package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A decode whose TARGET TYPE nothing determines — SHOULD be refused while
 * compiling, and is not.
 *
 * `T0215` refuses a dictionary demanded at a type variable no enclosing
 * function mentions, so this family ought to be its business. Two things keep
 * it from arriving, both diagnosed here and neither yet shippable:
 *
 *  - a top-level EXPRESSION has no `DictFrame`, so demands recorded while
 *    emitting it are dropped (see the first test);
 *  - the reach test asks a question about the receiver that does not answer for
 *    the result (same test);
 *  - and the METHOD-CALL form records no demand at all (last test).
 *
 * Each test carries the measurements for its own case. The three "accepted"
 * tests in the middle are the ones a fix must not break — the first attempt
 * broke one of them, which is why nothing shipped.
 */
class UndeterminedCodecTargetTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun err(src: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    @Test fun `a decode into an annotated binding is accepted`() {
        assertNull(err("""
            use std::codec::query::{ FromQuery }
            use std::codec::core::{ DecodeError }
            type Search = { q: Str }
            let f = {} -> do
              let q: Result Search DecodeError = "q=mezze".from_query {}
              0
            f {}
        """))
    }

    @Test fun `a decode consumed at a known type is accepted`() {
        assertNull(err("""
            use std::codec::query::{ FromQuery }
            type Search = { q: Str }
            let want = { s: Search } -> s.q
            let f = {} -> match "q=mezze".from_query {} is
              Ok { val } -> want { s = val }
              Err { error = _ } -> "bad"
            f {}
        """))
    }

    // ── still open: the METHOD-CALL form ─────────────────────────────────

    /**
     * ACCEPTED TODAY, and that is the remaining bug: `from_query` is reached as
     * a METHOD call, and no site records a demand for one.
     *
     * Measured, so the next attempt does not re-derive it:
     *
     *  - of the two recording sites, one is for a plain call to a constrained
     *    callee (this is not one), the other for a RECEIVER-LESS op;
     *  - `methodInfo.methodFunctionType` arrives with `abilityConstraints`
     *    ALREADY EMPTY, so the op's own `with` cannot be read there;
     *  - `methodInfo.typeArgs` holds the RECEIVER (measured: `[Str]`), so it
     *    cannot stand in for the produced type;
     *  - recording from the arrow's produced variables instead does not fire
     *    either — `solvedFnType` is null on this path;
     *  - the ability's shape, for whoever picks this up:
     *    `FromQuery typeParams=['a, 'in] recv='in`, so the produced variable is
     *    `typeParams[0]`, and the generalised reach test above already admits
     *    it once a demand exists.
     *
     * Exactly one demand in this file reaches the judge — the top-level call
     * above — which is how the remaining work was narrowed to "recording".
     */
    @Test fun `a method-call decode with no target type is accepted today`() {
        assertNull(err("""
            use std::codec::query::{ FromQuery }
            let f = {} -> do
              let q = "a=1".from_query {}
              0
            f {}
        """), "when this starts failing, the method-call form is fixed — invert this test")
    }
}
