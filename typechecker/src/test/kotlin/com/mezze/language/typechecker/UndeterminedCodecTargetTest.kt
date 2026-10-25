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
     * RECORDING IS SOLVED, and measured end to end — but deliberately NOT landed
     * here, for the reason two paragraphs down. A receiver-taking ability op
     * carries only its RECEIVER to emit (`MethodCallInfo.typeArgs`, which is the
     * monomorphizer's thread), so no dictionary demand is recorded at all. The
     * prototype added the whole class-arg tuple beside it — populated in
     * `ExprMethodConstraints` from `abilityTypeVars`, which is already built
     * there for the `AbilityRequired` — and recorded it in `buildMethodCall`:
     *
     *     SITE   FromQuery.from_query classArgs=['a, Str]
     *     DEMAND FromQuery args=['a, Str] frame=ok holdsVar=true
     *     JUDGE  FromQuery args=['a, Str] visible=[…] hidden=[…] reaches=FALSE
     *
     * WHAT STOPS IT is `requirementReachesHiddenVar`, and not the codec
     * exemption on its second line, which is a red herring here. Its contract is
     * "typeArgs, RECEIVER FIRST", and it answers the receiver position only:
     * a hidden receiver counts when `dispatchesWithoutAValue` — when some op
     * takes no `it` — because an op that takes the value can only run on a value
     * that exists. `from_query` DOES take `it`, so the answer is false.
     *
     * But the hidden variable here is not the receiver. `FromQuery 'a 'in` has
     * `recv = 'in`: the receiver is the `Str` being decoded and is determined,
     * while `'a` — the type decoded INTO, which is what picks the impl — is the
     * open one. The judge has no question for a non-receiver class argument.
     *
     * So the remaining work is to generalise that question: an impl is selected
     * by ALL of an ability's class arguments, so a hidden argument the impls
     * discriminate on makes the choice undeterminable, exactly as a hidden
     * receiver does for a value-less op.
     *
     * AND THE RECORDING CANNOT LAND BEFORE IT DOES. Recording the tuple in the
     * ability's own declaration order hands the judge a tuple whose first slot
     * is NOT the receiver, against its stated contract — and it then reads the
     * primary variable as a receiver and refuses a legitimate program:
     * `MethodLevelAbilityConstraintTest > chained ability methods with
     * constraints` fails with "'Inner' is needed at `'a`". Reordering to put the
     * receiver first contradicts the other thing the function does with the
     * tuple, `typeArgs.zip(annotations)` against an impl's targets, which is in
     * DECLARATION order. For a one-parameter ability the two orders coincide,
     * which is why neither has had to give way yet. So the judge's question and
     * the tuple's order have to be settled together, in one pass with the full
     * suite behind it.
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
