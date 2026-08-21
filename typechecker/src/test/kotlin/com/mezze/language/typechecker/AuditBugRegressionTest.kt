package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Regression tests for the confirmed soundness/correctness bugs from the deep
 * multi-agent typechecker audit (Aug 2026, memory: project_tc_audit_2026-08).
 *
 * Each @Test asserts the CORRECT (sound) behavior — so the ones that pin an
 * open bug FAIL today. They are written as independent @Test methods (bug
 * number in the method name) so the orchestrator can @Disabled-tag exactly the
 * failing ones and keep the suite green until each fix lands.
 *
 * Convention: `err(src)` type-checks a program and returns the diagnostic
 * message on a TypeCheckError, or null on success. A method that succeeds ⇒
 * program checks; assertNull ⇒ MUST check, assertNotNull ⇒ MUST be rejected.
 *
 * Any tag on a comment:
 *   EXPECTED-CORRECT  — asserts sound behavior the current checker gets WRONG.
 *   NOTE              — documents current (possibly-buggy) behavior; the real
 *                       defect is elsewhere (e.g. runtime) or not expressible.
 */
class AuditBugRegressionTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun err(src: String): String? =
        try {
            tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true)
            null
        } catch (e: TypeCheckError) {
            e.message
        }

    // ── bug256 (audit #5): effect-row callback laundering ─────────────────
    // A `<...>`-row callback PARAM whose HOF return row is closed swallows the
    // callback's performed effects. `launder` calls its effectful callback but
    // its own signature is pure, so `go`/`main` perform Boom with NO handler and
    // the unhandled effect escapes typed as pure. A sound checker unifies the
    // callback's effect row into the enclosing fn's return row, so the whole
    // program must be REJECTED (Boom performed, never handled).
    // EXPECTED-CORRECT: accepted today under bug256 (ExpressionConstraints.kt:599).
    @Disabled("known-bug #256: effect-row <...>-callback laundering not yet rejected; enable when fixed")
    @Test fun `bug256_effect_callback_laundering_is_rejected`() {
        val m = err(
            """
            effect Boom where {
              bang: { x: Int } -> Int <Boom>
            }

            let leaky = { x: Int } -> Boom.bang { x = x }

            let launder = { f: { x: Int } -> Int <...> } -> f { x = 1 }

            let go = {} -> launder { f = leaky }

            let main = {} -> go {}
            """
        )
        assertNotNull(
            m,
            "performing Boom through a <...>-row callback with no handler must be rejected, " +
                "not laundered to a pure type",
        )
    }

    // ── bug257 (audit #8): missing value restriction on a mutable cell ────
    // A top-level `tvar { init = [] }` generalizes the cell's ELEMENT type var,
    // so the one physical cell can be viewed at two incompatible element types
    // (Seq Int and Seq Str). That is unsound — the value-restriction gate must
    // NOT generalize a mutable-cell-carrying binding, so the second view is a
    // type error.
    // EXPECTED-CORRECT: accepted today under bug257 (ConstraintGenerator.kt:541).
    @Disabled("known-bug #257: no value restriction; polymorphic mutable cell wrongly generalized; enable when fixed")
    @Test fun `bug257_value_restriction_blocks_polymorphic_mutable_cell`() {
        val m = err(
            """
            use std::effects::stm::{ TVar, tvar }

            let box = tvar { init = [] }
            let asInt: TVar (Seq Int) = box
            let asStr: TVar (Seq Str) = box
            """
        )
        assertNotNull(
            m,
            "one mutable cell must not be viewable at both TVar (Seq Int) and TVar (Seq Str)",
        )
    }

    // ── bug257 control: pinned cell stays monomorphic (should already pass) ──
    // `tvar { init = 0 }` pins the element to Int; viewing it as TVar Str is a
    // plain type error today. This anchors that the checker DOES reject the
    // obvious case, isolating the generalization gap above.
    @Test fun `bug257_control_pinned_cell_rejects_wrong_view`() {
        val m = err(
            """
            use std::effects::stm::{ TVar, tvar }

            let box = tvar { init = 0 }
            let asStr: TVar Str = box
            """
        )
        assertNotNull(m, "an Int-pinned cell viewed as TVar Str must be rejected")
    }

    // ── bug258 (audit #7): both-open unifyVariants arity gap ───────────────
    // Unifying a nullary constructor use (`Foo`) with a payload use (`Foo { x }`)
    // of the same name must fail: the closed-variant branch checks payload
    // presence, but the both-open branch skips it, so a program that pins the
    // same ctor both ways slips through and destructuring crashes at runtime.
    // A sound checker rejects the conflicting arities.
    // EXPECTED-CORRECT: accepted today under bug258 (Unification.kt:566).
    // #258 fixed: both-open variant arity guard added to Unification.kt.
    @Test fun `bug258_both_open_constructor_arity_conflict_is_rejected`() {
        val m = err(
            """
            let nul = {} -> Foo
            let pay = {} -> Foo { x = 1 }
            let both = { b: Bool } -> if b then nul {} else pay {}
            """
        )
        assertNotNull(
            m,
            "nullary `Foo` and payload `Foo { x }` must not unify — conflicting constructor arity",
        )
    }

    // ── bug259 (audit #3): nested variant-payload cartesian product ────────
    // `Wrap { a: Bool, b: Bool }` has four inhabitants. A match that only covers
    // the diagonal `Wrap { True, True }` and `Wrap { False, False }` leaves
    // `Wrap { True, False }` (and `{ False, True }`) uncovered. The checker must
    // treat multi-field payloads as a product (like the #220 record routine),
    // so this is non-exhaustive and rejected.
    // EXPECTED-CORRECT: accepted today under bug259 (MatchValidator.kt:559).
    @Test fun `bug259_nested_product_diagonal_only_is_non_exhaustive`() {
        val m = err(
            """
            type Pair = | Wrap { a: Bool, b: Bool } |
            let f: { p: Pair } -> Int = { p } -> match p is
              Wrap { a = True,  b = True }  -> 1
              Wrap { a = False, b = False } -> 2
            """
        )
        assertNotNull(
            m,
            "diagonal-only match over a two-Bool payload leaves Wrap { True, False } uncovered",
        )
    }

    // ── bug259 control: full product is exhaustive (should already pass) ──
    @Test fun `bug259_control_full_product_is_exhaustive`() {
        val m = err(
            """
            type Pair = | Wrap { a: Bool, b: Bool } |
            let f: { p: Pair } -> Int = { p } -> match p is
              Wrap { a = True,  b = True }  -> 1
              Wrap { a = True,  b = False } -> 2
              Wrap { a = False, b = True }  -> 3
              Wrap { a = False, b = False } -> 4
            """
        )
        assertNull(m, "all four Bool×Bool inhabitants covered ⇒ exhaustive")
    }

    // ── bug260 (audit #9): open named-record alias invents fields ──────────
    // `type Foo = { a: Int, ... }` is an open alias with exactly one declared
    // field. Reading `f.zzz` off a `Foo`-typed param must be a type error — the
    // openness allows UNKNOWN extra fields at call sites, but the callee cannot
    // read a field the alias never declared. The inline `{ a: Int, ... }` form is
    // correctly rejected; the alias form must be too.
    // EXPECTED-CORRECT: accepted today under bug260 (ConstraintSolver.kt:1029 /
    // TypeAliasBuilder expands with RowVar.fresh() not userOpen()).
    @Disabled("known-bug #260: open named-record-alias param invents fields; enable when fixed")
    @Test fun `bug260_open_alias_field_invention_is_rejected`() {
        val m = err(
            """
            type Foo = { a: Int, ... }
            let readGhost = { f: Foo } -> f.zzz
            """
        )
        assertNotNull(
            m,
            "reading undeclared field `zzz` off an open alias `Foo` must be a type error",
        )
    }

    // ── bug260 control: the inline open-record form IS rejected ────────────
    // Anchors that the gate works for the inline form, isolating the alias gap.
    @Test fun `bug260_control_inline_open_record_field_invention_is_rejected`() {
        val m = err(
            """
            let readGhost = { f: { a: Int, ... } } -> f.zzz
            """
        )
        assertNotNull(m, "reading undeclared field `zzz` off inline open record must be rejected")
    }

    // ── bug261 (audit #11): untyped-lambda-param ability dispatch ──────────
    // An UNTYPED lambda param whose body calls an ability method (`.describe`),
    // resolved only via expected-type push, fails to pin the receiver — once ≥2
    // impls exist this is an unresolved CallIndirect that crashes at RUNTIME.
    // At the typecheck level the program currently PASSES (no static diagnostic),
    // so this test documents the exact program shape and asserts current accept.
    // NOTE: runtime-only crash (ExprFunctionConstraints.kt:221); NOT
    // EXPECTED-CORRECT — the TC-level accept is expected until a dispatch-pinning
    // diagnostic is added. Annotating the param (`x: Int`) is the known fix.
    @Test fun `bug261_untyped_param_ability_dispatch_typechecks_runtime_defect`() {
        val m = err(
            """
            ability Describe for 'a where {
              describe: { it: 'a } -> Str
            }

            impl Describe for Int where {
              describe = { it } -> "int"
            }

            impl Describe for Str where {
              describe = { it } -> "str"
            }

            let apply = { f: { x: 'a } -> Str, v: 'a } -> f { x = v }

            let go = {} -> apply { f = { x } -> x.describe {}, v = 5 }
            """
        )
        assertNull(
            m,
            "program type-checks today; the defect is a runtime unresolved-dispatch crash, " +
                "not a TC-level rejection",
        )
    }

    // ── bug262 (audit #2): same-named ability method, order-dispatch ───────
    // Two abilities each declare a `render` method for their own receiver type.
    // `filteredMatchingAbilities.first()` picks by registration order with no
    // ambiguity check; an annotation cannot redirect. A sound checker uses the
    // annotated / contextually-known receiver type to dispatch, so an
    // unambiguous annotated call must CHECK.
    // EXPECTED-CORRECT: may be wrongly rejected (or wrongly dispatched) today
    // (ExprMethodConstraints.kt:288).
    @Test fun `bug262_same_named_method_dispatches_by_annotation`() {
        val m = err(
            """
            ability RenderA for 'a where {
              render: { it: 'a } -> Str
            }

            ability RenderB for 'a where {
              render: { it: 'a } -> Str
            }

            impl RenderA for Int where {
              render = { it } -> "A"
            }

            impl RenderB for Str where {
              render = { it } -> "B"
            }

            # The receiver type is Int, so this must resolve to RenderA unambiguously.
            let useIt: { n: Int } -> Str = { n } -> n.render {}
            """
        )
        assertNull(
            m,
            "an unambiguous same-named method on a known receiver type must dispatch by type, " +
                "not registration order",
        )
    }

    // ── bug263 (audit #12): variant load-order candidate selection ─────────
    // `findVariantWithConstructors` returns Failure on the FIRST partial-match
    // candidate instead of scanning for a full cover, so a valid program is
    // accepted/rejected by import order. Reliably reproducing this needs TWO
    // modules with overlapping constructor sets (the resolver picks the first
    // partial candidate), which the single-module test harness here cannot
    // express (one parseParsedModule call, one source file).
    // NOTE: not expressible single-module; documented placeholder. This asserts
    // the tautological facts we CAN check in one module so the test is not a
    // bogus/empty assertion, and points at the real anchor (VariantSolver.kt:120).
    @Test fun `bug263_variant_load_order_documented_placeholder`() {
        // Single-module sanity: a variant whose ctors are fully covered resolves.
        val ok = err(
            """
            type C = | Alpha, Beta |
            let f: { c: C } -> Int = { c } -> match c is
              Alpha -> 1
              Beta  -> 2
            """
        )
        assertNull(ok, "a fully-covered single-module variant resolves cleanly")
        // The order-dependent partial-cover bug requires cross-module overlapping
        // constructor sets and cannot be reproduced in one source file here.
        // See project_tc_audit_2026-08 (#12) / VariantSolver.kt:120 for the anchor.
    }

    // ── bug270 (audit): or-pattern false-positive on unreachability ────────
    // `type C = | R, G, B |`. Arm 1 `R | G` covers R and G; arm 2 `G | B` covers
    // G (redundantly) AND B (uniquely). Arm 2 is NOT wholly unreachable because
    // it is the only arm covering B, so the match is exhaustive and must be
    // ACCEPTED. The current unreachability check flags arm 2 as dead.
    // EXPECTED-CORRECT: wrongly rejected today.
    @Disabled("known-bug #270: or-pattern redundancy false-positive rejects legal exhaustive match; enable when fixed")
    @Test fun `bug270_or_pattern_partial_overlap_is_accepted`() {
        val m = err(
            """
            type C = | R, G, B |
            let f: { c: C } -> Int = { c } -> match c is
              R | G -> 1
              G | B -> 2
            """
        )
        assertNull(
            m,
            "arm 2 `G | B` uniquely covers B ⇒ reachable and exhaustive; must be accepted",
        )
    }

    // ── bug271 (audit): codec/type-printer internal throw ──────────────────
    // A sound checker turns every failure into a caught TypeCheckError — never
    // an uncaught IllegalArgumentException from CodecGenerator or the type
    // printer. Deriving/using a codec (or ==) for an unsupported newtype shape is
    // one candidate trigger. We cannot confidently construct the exact
    // raw-throw path from source, so this is a documenting probe: the program
    // below is unsound (newtype used with `==` without an Eq impl) and MUST be
    // rejected as a proper TypeCheckError. Because `err()` only catches
    // TypeCheckError, a raw IllegalArgumentException would make this test THROW
    // (fail) — which is exactly the failure mode this test guards against.
    // NOTE: best-effort trigger; asserts "rejection is a TypeCheckError, not a
    // raw exception" rather than a specific internal path.
    @Test fun `bug271_error_path_is_a_typecheckerror_not_a_raw_throw`() {
        val m = err(
            """
            newtype Wrapped = Wrapped Int
            let cmp = { a: Wrapped, b: Wrapped } -> a == b
            """
        )
        assertNotNull(
            m,
            "using == on a newtype without an Eq impl must surface as a TypeCheckError, " +
                "never an uncaught internal exception",
        )
    }
}
