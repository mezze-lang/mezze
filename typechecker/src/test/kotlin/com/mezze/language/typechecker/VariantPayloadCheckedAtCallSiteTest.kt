package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A variant constructor's payload must be checked against the type the context
 * requires, including when the constructor is an ARGUMENT.
 *
 *     let want = { r: Result Search Str } -> 0
 *     want { r = Ok { val = 5 } }
 *
 * was ACCEPTED: an `Int` arrived where a `Search` record was declared. The same
 * constructor under an annotated binding was correctly rejected, and that
 * contrast is what made this look like a variant-unification problem instead of
 * a missing pass.
 *
 * The cause: a constructor whose variant is still a type VARIABLE defers in the
 * per-binding solve, and `HasConstructor` was STRIPPED before the global solve,
 * so nothing ever came back to finish the check. An argument is always that
 * case — the parameter's declared type arrives through the application's own
 * equality, after the constructor has already deferred. `HasField` had the
 * identical blanket strip and was rescued from it with a completion mode; this
 * is the same bug in the constraint next door.
 *
 * Why the check runs AFTER the solve rather than inside it: letting
 * `HasConstructor` into the global solve perturbs it rather than completing it.
 * Measured, 25 regressions — the fixpoint partitions the other constraints
 * around it, a constructor on a still-open variant defers forever, and
 * expanding a nominal type to its variant shape puts a second `Bool` into
 * circulation, which reads as "Bool does not implement 'Show'" printed directly
 * beside "Available impls: impl Show for Bool". See `checkConstructorPayloads`.
 */
class VariantPayloadCheckedAtCallSiteTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun err(src: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    /** The bug itself: the payload of a constructor passed as an argument. */
    @Test fun `a constructor argument's payload is checked`() {
        assertNotNull(
            err("""
                type Search = { q: Str }
                let want = { r: Result Search Str } -> 0
                want { r = Ok { val = 5 } }
            """),
            "an Int payload must not satisfy `Result Search Str`",
        )
    }

    /** One level deeper: the payload record's own field. */
    @Test fun `a constructor argument's payload FIELD is checked`() {
        assertNotNull(
            err("""
                type Search = { q: Str }
                let want = { r: Result Search Str } -> 0
                want { r = Ok { val = { q = 5 } } }
            """),
            "`q = 5` must not satisfy `q: Str`",
        )
    }

    /**
     * The contrast that hid it: an annotated BINDING was always checked, because
     * there the per-binding solve has the annotation in hand when the
     * constructor is solved.
     */
    @Test fun `a constructor in an annotated binding was already checked`() {
        assertNotNull(
            err("""
                type Search = { q: Str }
                let f = {} -> do
                  let r: Result Search Str = Ok { val = 5 }
                  0
                f {}
            """),
            "the control: this was never the broken case",
        )
    }

    /**
     * THE BOUNDARY, and the program that found all of this.
     *
     * A `match` arm's requirement on its binding does refine the scrutinee —
     * `describe` infers `{ r: Result { to_upper: {} -> Str } 'a } -> Str`, and
     * that payload is the receiver GROWN for the method call, not a record the
     * program has. Growing it is how a non-homogeneous method family on a
     * polymorphic receiver gets typed at all, with dispatch left to the runtime
     * (`HasField.methodCallee`), so the call site has nothing it can soundly
     * compare `Int` against and this stays ACCEPTED here.
     *
     * The same requirement expressed through an ordinary function IS enforced —
     * the test above — which is the distinction worth keeping: the gap is
     * method-grown receivers, not match arms.
     *
     * Do not "fix" this by comparing against the grown shape anyway. That is
     * exactly what `InspectPolymorphicReceiverTest.toStrOnMatchArmBindingStillWorks`
     * forbids: `{ a } -> match a is Grams { n } -> n.to_str {}` called with
     * `Grams { n = 4 }` is a legitimate program, and comparing its payload
     * against the grown record rejects it with "expected Int, got
     * { to_str: {} -> 'a }".
     */
    @Test fun `a method call on a grown receiver stays deferred to runtime`() {
        assertNull(
            err("""
                let describe = { r } -> match r is
                  Ok { val } -> val.to_upper {}
                  Err { error = _ } -> "bad"
                describe { r = Ok { val = 5 } }
            """),
            "the receiver is method-grown, so the payload is not comparable",
        )
    }

    /** The same through a constrained function rather than a method. */
    @Test fun `an arm's requirement through a called function is enforced`() {
        assertNotNull(
            err("""
                type Search = { q: Str }
                let want = { s: Search } -> s.q
                let describe = { r } -> match r is
                  Ok { val } -> want { s = val }
                  Err { error = _ } -> "bad"
                describe { r = Ok { val = 5 } }
            """),
            "the arm needs Search; the call passes Int",
        )
    }

    /** And with the scrutinee's error slot written as a type param. */
    @Test fun `an arm's requirement is enforced under a declared scrutinee`() {
        assertNotNull(
            err("""
                type Search = { q: Str }
                let want = { s: Search } -> s.q
                let describe = { r: Result 'a Str } -> match r is
                  Ok { val } -> want { s = val }
                  Err { error = _ } -> "bad"
                describe { r = Ok { val = 5 } }
            """),
            "declared `'a`, Int payload",
        )
    }

    // ── the controls a fix must not break ────────────────────────────────

    /**
     * Backward refinement through a PLAIN record param already worked, which is
     * what localised the break to the variant payload.
     */
    @Test fun `backward refinement through a plain record param still works`() {
        assertNotNull(
            err("""
                let describe = { r } -> r.val.to_upper {}
                describe { r = { val = 5 } }
            """),
            "no variant involved; this was never broken",
        )
    }

    /** An arm checked against a CONCRETE payload was always rejected. */
    @Test fun `an arm body against a concrete payload is still rejected`() {
        assertNotNull(
            err("""
                type Search = { q: Str }
                let want = { s: Search } -> s.q
                let describe = { r: Result Int Str } -> match r is
                  Ok { val } -> want { s = val }
                  Err { error = _ } -> "bad"
                describe { r = Ok { val = 5 } }
            """),
            "Int payload into a Search-taking call",
        )
    }

    /** A plain mismatched argument at the same position. */
    @Test fun `a plain mismatched argument is still rejected`() {
        assertNotNull(
            err("""
                type Search = { q: Str }
                let want = { r: Result Search Str } -> 0
                want { r = 5 }
            """),
            "Int where `Result Search Str` is declared",
        )
    }

    /**
     * A CORRECT constructor argument must still be accepted — the check must
     * reject a mismatch, not a constructor.
     */
    @Test fun `a correct constructor argument is accepted`() {
        assertNull(
            err("""
                type Search = { q: Str }
                let want = { r: Result Search Str } -> 0
                want { r = Ok { val = { q = "mezze" } } }
            """),
        )
    }

    /** And the other arm of the same variant. */
    @Test fun `a correct Err argument is accepted`() {
        assertNull(
            err("""
                type Search = { q: Str }
                let want = { r: Result Search Str } -> 0
                want { r = Err { error = "nope" } }
            """),
        )
    }

    /**
     * An OPEN variant must still grow a constructor it does not have: the check
     * leaves an absent constructor alone, which is what keeps the structural
     * alias rules (VariantStructuralAliasExtTest) working.
     */
    @Test fun `an open variant still grows a new constructor`() {
        assertNull(
            err("""
                let pick = { b: Bool } -> if b then Red else Green
                let name = { c } -> match c is
                  Red -> "red"
                  Green -> "green"
                  _ -> "other"
                name { c = pick { b = True } }
            """),
        )
    }
}
