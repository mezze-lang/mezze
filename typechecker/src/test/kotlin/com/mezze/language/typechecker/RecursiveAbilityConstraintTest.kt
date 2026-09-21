package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Recursive / dependent ability usage:
 *   - an impl body that invokes the SAME ability on a wrapped sub-value,
 *   - a method-level dependent constraint (`… with Eq 'item …`) on an ability
 *     that ALSO carries a `having` associated type — the two features combined.
 *
 * ([MethodLevelAbilityConstraintTest] covers method-level constraints on their
 *  own; this adds the recursion + the dependent-constraint × associated-type mix.)
 */
class RecursiveAbilityConstraintTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun err(b: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(b, "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    @Test
    fun `an impl body may invoke the same ability on a wrapped value`() {
        assertNull(err("""
            ability Dbg for 'a where { dbg: { it: 'a } -> Str }
            impl Dbg for Int where { dbg = { it } -> "n" }
            type IntBox = | IntBox { inner: Int } |
            impl Dbg for IntBox where {
              dbg = { it } -> match it is IntBox { inner } -> inner.dbg {}
            }
            pub let f = {} -> (IntBox { inner = 5 }).dbg {}
        """.trimIndent()), "an impl calling `.dbg {}` on its wrapped Int must typecheck")
    }

    @Test
    fun `method-level dependent constraint on a having-ability, concrete receiver`() {
        assertNull(err("""
            ability Box for 'c having 'item where { peek: { it: 'c } with Eq 'item -> 'item }
            impl Box for [Int] having 'item = Int where { peek = { it } -> 0 }
            pub let f = {} -> ([1]).peek {}
        """.trimIndent()), "Int has Eq, so peek's `with Eq 'item` is satisfied")
    }

    @Test
    fun `method-level dependent constraint on a having-ability, generic receiver`() {
        assertNull(err("""
            ability Box for 'c having 'item where { peek: { it: 'c } with Eq 'item -> 'item }
            impl Box for [Int] having 'item = Int where { peek = { it } -> 0 }
            pub let f = { xs } -> xs.peek {}
        """.trimIndent()), "a generic peek call carrying the Box + dependent Eq constraint must typecheck")
    }

    /**
     * FIXED. The same program is now rejected with and without the annotation.
     *
     * It used to be ACCEPTED unannotated and REJECTED with `: {} -> Sink` written
     * on it — an annotation deciding whether a program type-checks, which the
     * language forbids outright.
     *
     * The obligation was always generated at the call site (`AbilityRequired(Eq)`,
     * context "Method-level constraint: Eq on Box.peek") and `Sink` was always
     * correctly judged to have no Eq — `DerivabilityRules` resolves the
     * `TypeApplication(Sink)` to its declaration and honours "a newtype derives
     * Inspect and Hash ONLY". What went wrong was in between: for an UNANNOTATED
     * binding the obligation was ABSORBED into the inferred signature as a
     * `with Eq …` constraint instead of being discharged, and since `f` is never
     * called, nothing ever demanded it. The annotation forbids absorption — it says
     * the type has no such constraint — so the obligation had to discharge, and
     * correctly failed.
     *
     * Absorption is wrong here for a specific reason: the argument is CONCRETE. A
     * concrete type either has the ability or it does not; there is nothing to
     * generalize over, so `with Eq Sink` on the signature is not a weaker claim, it
     * is a meaningless one. `addAbilityConstraintsToFunctionType` now reports such a
     * constraint instead of carrying it, against the obligation that raised it.
     *
     * The oracle it asks had to be fixed first. It was one boolean, "is this
     * discharged", so "no impl for a concrete type" and "still open, cannot say"
     * were the same answer; reporting on it rejected `Tryable (Result Int HttpError)`
     * where HttpError is an open variant — the row is open, the impl exists. It now
     * answers DISCHARGED / UNSATISFIED / UNDECIDED and only the middle is reported.
     *
     * The premise also had to be fixed to see this at all. The case used to write
     * `type Sink = | Sink {} |` and assert "Sink has no Eq", which stopped being
     * true when Eq became derived for every non-newtype — so it was passing for the
     * wrong reason. A newtype genuinely has no Eq.
     */
    @Test
    fun `dependent constraint unsatisfied is rejected`() {
        // The item type has no `Eq`, so peek's `with Eq 'item` cannot be satisfied.
        //
        // It must be a NEWTYPE. This case used to write `type Sink = | Sink {} |`
        // and assert "Sink has no Eq" — which stopped being true when Eq became
        // derived for every non-newtype. The program was well-typed and the test
        // passed only because `'item` never resolved, so the check was rejecting for
        // the wrong reason; it went red the moment the solve started resolving
        // `'item`. A newtype derives Inspect and Hash ONLY, so this one genuinely
        // has no Eq and the dependent constraint genuinely cannot be discharged.
        val e = err("""
            ability Box for 'c having 'item where { peek: { it: 'c } with Eq 'item -> 'item }
            newtype Sink = Int
            impl Box for [Sink] having 'item = Sink where { peek = { it } -> Sink.(0) }
            pub let f = {} -> ([Sink.(1)]).peek {}
        """.trimIndent())
        assertNotNull(e, "the item newtype has no Eq, so peek's dependent constraint must fail")
    }

    /**
     * The half that WORKS, locked in: with the return type written, the very same
     * program is rejected and names both the ability and the type.
     *
     * This is what makes the case above a hole rather than a missing feature — the
     * checker can decide this, and does, the moment absorption is not available to
     * it. It is also the measurement behind "an annotation changes what the program
     * means": these two tests differ by `: {} -> Sink` and nothing else.
     */
    @Test
    fun `an annotated binding discharges the dependent constraint and is rejected`() {
        val e = err("""
            ability Box for 'c having 'item where { peek: { it: 'c } with Eq 'item -> 'item }
            newtype Sink = Int
            impl Box for [Sink] having 'item = Sink where { peek = { it } -> Sink.(0) }
            pub let f: {} -> Sink = {} -> ([Sink.(1)]).peek {}
        """.trimIndent())
        assertNotNull(e, "annotated, the unsatisfiable `with Eq 'item` must be reported")
        assertTrue(
            e!!.contains("Eq") && e.contains("Sink"),
            "the error must name the ability and the type; got: $e",
        )
    }
}
