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

    @Test fun `a decode consumed by a direct call is accepted`() {
        assertNull(err("""
            use std::codec::query::{ FromQuery }
            use std::codec::core::{ DecodeError }
            type Search = { q: Str }
            let want = { s: Result Search DecodeError } -> 0
            let f = {} -> want { s = "q=mezze".from_query {} }
            f {}
        """))
    }

    @Test fun `a decode returned at a declared type is accepted`() {
        assertNull(err("""
            use std::codec::query::{ FromQuery }
            use std::codec::core::{ DecodeError }
            type Search = { q: Str }
            let f: { s: Str } -> Result Search DecodeError = { s } -> s.from_query {}
            f { s = "q=mezze" }
        """))
    }

    // ── still open: the METHOD-CALL form ─────────────────────────────────

    /**
     * ACCEPTED TODAY, and the remaining step is now a DIFFERENT one from the
     * recording that used to be missing.
     *
     * THREE STEPS, all three measured, NONE of them in the tree — because the
     * first two cannot be tested until the third lands, and landing the first
     * one alone regresses three tests. Each was built and run, so this is a
     * record of working code, not a sketch.
     *
     *  1. RECORDING, which is what used to be missing. A receiver-taking ability
     *     op carries only its RECEIVER to emit (`MethodCallInfo.typeArgs`, the
     *     monomorphizer's thread), so no dictionary demand was recorded at all.
     *     Carrying the whole class-arg tuple beside it — populated in
     *     `ExprMethodConstraints` from `abilityTypeVars`, which is already built
     *     there for the `AbilityRequired` — and recording it in
     *     `buildMethodCall` puts the demand in front of the judge:
     *
     *         SITE   FromQuery.from_query classArgs=['a, Str]
     *         DEMAND FromQuery args=['a, Str] frame=ok holdsVar=true
     *
     *  2. THE RECEIVER'S POSITION, a real bug in the judge.
     *     `requirementReachesHiddenVar` reads `typeArgs.first()` as the
     *     receiver, which is wrong for any ability declaring `for` on a later
     *     parameter — `FromQuery 'a 'in` has `recv = 'in` — and is harmless only
     *     because every caller today passes a single argument, where the two
     *     orders coincide. The position is available from the declaration
     *     (`receiverTypeParam` indexed into `typeParams`), and the same "nothing
     *     can supply this" question then applies to a hidden NON-receiver
     *     argument the impls discriminate on. Step 1 WITHOUT this refuses a
     *     legitimate program: `MethodLevelAbilityConstraintTest > chained
     *     ability methods with constraints`, "'Inner' is needed at `'a`".
     *
     *  3. THE CODEC EXEMPTION, which is a decision rather than an
     *     investigation, and is why 1 and 2 are not landed. With both in place
     *     this call is still accepted: the chain from `FromQuery` reaches its
     *     blanket impl, whose `with Decode QueryC 'a Str` is what actually
     *     discriminates, and the judge short-circuits on its second line for any
     *     codec ability — "Codec abilities are decided per call site by their
     *     own resolver and are not judged here."
     *
     *     That resolver does not decide this one.
     *     `checkCodecAbilityImplementation` returns false the moment any of its
     *     types holds a variable (`allTypes.any { containsVarType(it) }`), and
     *     the obligation is then DEFERRED — correctly, because a polymorphic
     *     function legitimately carries `with Decode QueryC 'a Str` to its
     *     caller. Nobody asks the one question that separates that case from
     *     this one: whether any enclosing function carries it.
     *
     *     So the exemption has to narrow — a codec ability whose DATA slot is a
     *     hidden variable has no impl any caller could supply — and with 1 and 2
     *     already built, that narrowing is the whole remaining change. It is
     *     also the risky part: it decides codec policy and can refuse working
     *     polymorphic codec code, and two of the three regressions step 1 showed
     *     on its own were `undefined_variable` error-message tests, i.e. this
     *     demand firing on programs that already have an error and changing
     *     which diagnostic is reported. That needs the full suite and a decision
     *     about precedence, not a rider on another fix.
     *
     * Two things that were suspected and are NOT the problem, measured:
     *  - the codec exemption (`isCodecAbility`) is never reached for this call;
     *  - backward inference is fine. `a decode consumed at a known type is
     *    accepted` below looked like a false positive in waiting, because `'a`
     *    read as open at emit; a `match` arm's requirement DOES refine its
     *    scrutinee. See [VariantPayloadCheckedAtCallSiteTest], which came out of
     *    chasing that and found a real soundness hole at the call site instead.
     */
    @Test fun `a method-call decode with no target type is accepted today`() {
        assertNull(err("""
            use std::codec::query::{ FromQuery }
            let f = {} -> do
              let q = "a=1".from_query {}
              0
            f {}
        """), "when this starts failing, the judge learned to ask about a non-receiver class argument — invert this test")
    }
}
