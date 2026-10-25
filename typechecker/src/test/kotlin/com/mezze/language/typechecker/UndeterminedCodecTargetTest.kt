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
     * IT WAS BUILT, IT WORKS ON THIS CALL, AND IT IS NOT LANDED. Written and
     * run end to end: with the three changes [UndeterminedClassArgTest] lists
     * (carry the whole class-arg tuple to emit; read the receiver's DECLARED
     * position, which a `with` clause may omit entirely; do not ask the receiver
     * question of a method call) plus the narrowing below, this exact call is
     * refused — the test above fires, as its own marker predicted.
     *
     * THE NARROWING. `requirementReachesHiddenVar` short-circuits on its second
     * line for any codec ability: "Codec abilities are decided per call site by
     * their own resolver and are not judged here." That resolver does not decide
     * this one. `checkCodecAbilityImplementation` returns false the moment any
     * of its types holds a variable (`allTypes.any { containsVarType(it) }`) and
     * the obligation is then DEFERRED — right for a polymorphic function, since
     * `{ s: Str } with Decode QueryC 'a Str -> …` carries it to a caller who
     * determines `'a`. Nothing then asks whether a caller exists. Since a codec
     * impl is generated per CONCRETE tuple, a non-receiver slot holding a
     * variable no enclosing function names has no impl anyone could supply, so
     * the exemption becomes: refuse exactly that, keep it otherwise. `Format for
     * 'config having 'out` has nothing but a receiver and is asked nothing.
     *
     * WHY IT CANNOT LAND: it produces one false positive, on `a decode consumed
     * at a known type is accepted` below — a WORKING program. There `'a` is
     * determined, by the `Ok` arm feeding `want { s = val }`, and the judge
     * cannot see it. Measured, with both substitutions in hand:
     *
     *     JUDGE FromQuery args=['a, Str] visible=[] perBinding=[#5987->'a]
     *
     * so the variable is open in the composed substitution AND in the
     * per-binding one. The link that carries `Search` runs through the `Ok`
     * pattern — a `HasConstructor` constraint — and `collectGlobalConstraints`
     * STRIPS those before the global solve. The information is lost, not merely
     * masked, so no amount of asking at the judge can recover it. Consulting the
     * per-binding substitution was tried for exactly this and does not help.
     *
     * The contrast that proves it: `a decode consumed by a direct call is
     * accepted` passes, and resolves to `[Search, Str]` at emit. Same program
     * shape, no pattern in the middle.
     *
     * So the prerequisite is the HasConstructor strip, not codec policy — the
     * same strip that hid the soundness hole in
     * [VariantPayloadCheckedAtCallSiteTest].
     *
     * CORRECTION, and this is the real finding: for a decode ability the
     * question CANNOT BE ASKED AT EMIT AT ALL, by design. Running the whole rule
     * against the full suite turned up 9 false positives, 7 of them one stdlib
     * site — `std::effects::http::from_json_result`, which declares
     * `with FromJson 'a Str -> Result 'a HttpError`, so the type is written in
     * its own signature and no policy could justify refusing it. Measured:
     *
     *     per-binding : annotation #18708 → #21126, body usage #21162 → #21126
     *     emit        : visible {18708, 21126}, demand carries #21162 (bare)
     *
     * One written `'a`, related correctly while the binding is being solved, and
     * bare by the time emit looks. `ConstraintGenerator` says why, in its own
     * words, where it builds the accumulated per-binding substitution:
     *
     *     One EXCLUSION, and it is structural: a decode target. `from_json`
     *     returns `Result 'a DecodeError` where `'a` is inferred from the fields
     *     the program later reads, and that variable must stay BARE where emit
     *     reads it — codegen resolves each call's `.decode` dictionary per call
     *     site (DecodeTargetResolver), and a call node carrying the record
     *     instead of the variable wires the wrong decoder.
     *
     * `decodeTargetVars` are filtered OUT of that substitution deliberately, and
     * `isDecodeEntryAbility` is exactly `["FromJson"]`. So "bare at emit" carries
     * no information about whether the program determines the type — it is the
     * guaranteed state for every correct decode. An emit-time judge therefore
     * false-positives on all of them, and no amount of resolving helps: resolving
     * through the per-binding substitution was tried and the mapping is not in
     * there either, because it was removed on purpose.
     *
     * SO THE QUESTION BELONGS IN THE PER-BINDING STEP, where the variable is
     * still related — the same place `AnnotationValidator`
     * `.validateAbilityOnCorrectTypeVar` already compares a body's ability usage
     * against the declared constraints, and already has `collectTypeVars(`
     * `annotationType)` for "what this signature names". That is where to build
     * it, and it explains why four attempts at emit all failed.
     *
     * Two earlier conclusions in this file were WRONG and are corrected above:
     * that the blocker was codec policy, and that it was the HasConstructor
     * strip. The strip findings still stand on their own (see
     * [[project_stripped_constraint_is_an_unchecked_obligation]]) — letting those
     * constraints into the global solve breaks 11 tests even when the solver
     * branch is completely inert, which is worth knowing — but the strip is not
     * what blocks THIS rule.
     *
     * The other two false positives have their own causes and are NOT this one:
     * the `FromQuery` case below (FromQuery is neither decode-flavored nor a
     * decode entry ability, so the exclusion does not apply to it), and
     * `NumAdd` at `'a 'a` inside `impl Hashable for MyRecord where { digest =`
     * `{ it } -> it.x + it.y }`, which is unmeasured — note that the same filter
     * also drops `methodGrownReceiverVars`, one line away.
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
