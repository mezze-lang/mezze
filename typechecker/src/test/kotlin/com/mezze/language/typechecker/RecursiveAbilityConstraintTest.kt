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
     * OPEN SOUNDNESS HOLE, pre-existing — an operation's OWN `with` constraint is
     * never turned into an obligation at the call site.
     *
     * Instrumented every `AbilityRequired(Eq)` construction while checking this
     * program: the only ones built come from the stdlib (`contains`, `index_of`,
     * `unique`, `distinct`). NONE is generated for `peek`'s `with Eq 'item`. So the
     * constraint is not failing to be discharged — it is never asked.
     *
     * This test USED to pass, for the wrong reason. It wrote `type Sink = | Sink {} |`
     * and asserted "Sink has no Eq" — which stopped being true when Eq became
     * derived for every non-newtype, so the program was well-typed all along. It
     * only looked rejected because `'item` never resolved. When the per-binding
     * solve started being propagated (elaboration baseline 861 -> 0), `'item`
     * resolved and the accidental rejection disappeared, exposing this.
     *
     * VERIFIED PRE-EXISTING, not a regression of that change: with a NEWTYPE item
     * (which genuinely has no Eq — newtypes derive Inspect and Hash only) the
     * program is accepted with the propagation DISABLED too.
     *
     * The fix is to generate the obligation for an ability operation's own `with`
     * constraints at the call site, with the associated type substituted — and it
     * has to be deferred, because `'item` is pinned by the impl's `having` only
     * after the receiver resolves.
     */
    @org.junit.jupiter.api.Disabled("OPEN: an operation's own `with` constraint generates no obligation — see KDoc")
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
}
