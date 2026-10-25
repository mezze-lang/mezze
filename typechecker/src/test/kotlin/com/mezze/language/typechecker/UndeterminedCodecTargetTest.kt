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
     * TWO WAYS OF LIFTING THAT STRIP WERE TRIED AND BOTH FAIL. Do not repeat
     * them:
     *
     *  - LETTING THE CONSTRAINTS INTO THE GLOBAL SOLVE breaks 11 tests, and NOT
     *    because of anything they compute. Measured with the solver branch made
     *    completely INERT — returning Deferred always, publishing nothing at all
     *    — and the same 11 failed identically ("Bool does not implement 'Show'"
     *    printed beside "Available impls: impl Show for Bool"; "'Pink' is not a
     *    valid constructor"). Their mere PRESENCE is what matters:
     *    `solveHasConstructorFixpoint` partitions every other constraint around
     *    them and runs a different number of rounds. So the earlier guess that
     *    publishing an expanded variant was the cause is WRONG, and filtering
     *    what gets published does not help.
     *
     *  - COMPUTING THE LINK AND INJECTING IT AS A PLAIN EQUALITY (pattern
     *    contexts only, only where the variant is already concrete under the
     *    per-binding substitution, so the partition is untouched) clears all 11
     *    — and still does not fix this call: `'a` reads as open at emit exactly
     *    as before. It also opens a NEW elaboration gap, at `let:flatten`
     *    (`ElaborationInvariantGateTest`, and `ConstructorInMatchArmElaboration`
     *    `Test > bare constructor last …` goes 0 → 1). That is the area
     *    [[project_one_written_typevar_two_typevars]] #5 warns about by name:
     *    closing `flatten` opens five other rows.
     *
     * What is left to find out: WHY the link does not arrive even when the
     * equality is in the solve. The next measurement is the one not taken — the
     * `HasField` that ties the arm's binding to the payload record's field runs
     * in `completionOnly` mode, which acts only when its receiver is ALREADY a
     * concrete record, and it may simply never be retried after the equality
     * lands. That is a question about the fixpoint's retry order, and it is
     * where to start.
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
